package io.casehub.qhorus.runtime.message;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import jakarta.enterprise.inject.Instance;

import io.casehub.qhorus.api.gateway.MessageObserver;
import io.casehub.qhorus.api.gateway.OutboundMessage;
import io.casehub.qhorus.api.spi.ObligorTrustContext;
import io.casehub.qhorus.api.spi.ObligorTrustPolicy;
import io.casehub.qhorus.api.message.DispatchResult;
import io.casehub.qhorus.api.message.MessageDispatch;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.casehub.qhorus.runtime.channel.AllowedWritersPolicy;
import io.casehub.qhorus.runtime.channel.Channel;
import jakarta.transaction.TransactionSynchronizationRegistry;

import io.casehub.qhorus.runtime.channel.ChannelService;
import io.casehub.qhorus.runtime.channel.RateLimiter;
import io.casehub.qhorus.runtime.config.QhorusConfig;
import io.casehub.qhorus.runtime.gateway.ChannelGateway;
import io.casehub.qhorus.runtime.instance.InstanceService;
import io.casehub.qhorus.runtime.ledger.LedgerWriteOutcome;
import io.casehub.qhorus.runtime.ledger.LedgerWriteService;
import io.casehub.qhorus.runtime.store.MessageStore;
import io.casehub.qhorus.runtime.store.query.MessageQuery;

/**
 * Application service for dispatching channel messages.
 *
 * <p>{@link #dispatch} is the single enforcement gate for all channel write policy:
 * paused check, writer ACL, rate limiting, LAST_WRITE overwrite semantics, and
 * fanOut to external backends. Every caller — MCP tools, A2A, human backends —
 * receives enforcement automatically. No caller can bypass it.
 *
 * <p>MCP-specific concerns (artefact lifecycle, deadlines, content validation)
 * remain in {@code QhorusMcpTools.sendMessage()}.
 *
 * <p>EVENT messages bypass ACL and rate limiting — telemetry always flows.
 */
@ApplicationScoped
public class MessageService {

    @Inject
    ChannelService channelService;

    @Inject
    MessageStore messageStore;

    @Inject
    CommitmentService commitmentService;

    @Inject
    MessageTypePolicy messageTypePolicy;

    @Inject
    Instance<MessageObserver> observers;

    @Inject
    LedgerWriteService ledgerWriteService;

    @Inject
    AllowedWritersPolicy allowedWritersPolicy;

    @Inject
    RateLimiter rateLimiter;

    @Inject
    QhorusConfig config;

    @Inject
    ObligorTrustPolicy obligorTrustPolicy;

    @Inject
    TransactionSynchronizationRegistry tsr;

    // Deliberate CDI cycle: MessageService → ChannelGateway → MessageService (via receiveHumanMessage/receiveObserverSignal).
    // Both are @ApplicationScoped (normal scope). Arc resolves via client proxies — verified by full build and @QuarkusTest.
    @Inject
    ChannelGateway channelGateway;

    @Inject
    InstanceService instanceService;

    /**
     * Dispatches a message to a channel, enforcing all channel write policies.
     *
     * <p>Enforcement sequence:
     * <ol>
     *   <li>Channel paused check</li>
     *   <li>Writer ACL (skipped for EVENT)</li>
     *   <li>Rate limit check (skipped for EVENT)</li>
     *   <li>Trust gate — COMMAND to named obligor, via {@link io.casehub.qhorus.api.spi.ObligorTrustPolicy} SPI</li>
     *   <li>Message type policy</li>
     *   <li>LAST_WRITE update-in-place (if applicable)</li>
     *   <li>Normal insert</li>
     *   <li>Rate limit recording</li>
     *   <li>fanOut to external backends</li>
     * </ol>
     *
     * <p>Ledger entry write failures propagate — the caller's {@code @Transactional}
     * will roll back. Attestation write failures are caught and logged.
     */
    @Transactional
    public DispatchResult dispatch(final MessageDispatch dispatch) {
        final Channel ch = channelService.findById(dispatch.channelId()).orElse(null);

        // ── Paused check ──────────────────────────────────────────────────────
        if (ch != null && ch.paused) {
            throw new IllegalStateException(
                    "Channel '" + ch.name + "' is paused — send_message blocked. Use resume_channel to re-enable.");
        }

        // ── Writer ACL (EVENT bypasses — telemetry always flows) ──────────────
        if (ch != null && dispatch.type() != MessageType.EVENT) {
            final String sender = dispatch.sender();
            if (!allowedWritersPolicy.isAllowedWriter(sender, ch.allowedWriters, () -> {
                final List<String> tags = new ArrayList<>(
                        instanceService.findCapabilityTagsForInstance(sender));
                tags.add("role:" + dispatch.actorType().name().toLowerCase());
                return tags;
            })) {
                throw new IllegalStateException(
                        "Sender '" + sender + "' is not permitted to write to channel '" + ch.name
                                + "'. Channel has an allowed_writers ACL.");
            }
        }

        // ── Rate limit (EVENT bypasses) ───────────────────────────────────────
        if (ch != null && dispatch.type() != MessageType.EVENT) {
            final String rateLimitError = rateLimiter.check(
                    ch.id, ch.name, dispatch.sender(), ch.rateLimitPerChannel, ch.rateLimitPerInstance);
            if (rateLimitError != null) {
                throw new IllegalStateException(rateLimitError);
            }
        }

        // ── Trust gate (COMMAND + specific obligor only) ──────────────────────────
        // Role/capability-prefixed targets (containing ':') are broadcast intents with
        // no specific obligor to gate. Threshold management and gate-enable logic are
        // delegated to ObligorTrustPolicy — DefaultObligorTrustPolicy returns true when
        // minObligorTrust=0 (gate disabled). Refs #213.
        if (ch != null && dispatch.type() == MessageType.COMMAND
                && dispatch.target() != null
                && !dispatch.target().contains(":")) {
            if (!obligorTrustPolicy.permits(
                    new ObligorTrustContext(dispatch.target(), ch.id, ch.name))) {
                throw new IllegalStateException(
                        "COMMAND rejected: obligor '" + dispatch.target()
                        + "' did not meet the trust threshold");
            }
        }

        // ── Type policy ───────────────────────────────────────────────────────
        if (ch != null) {
            messageTypePolicy.validate(ch, dispatch.type());
        }

        // ── LAST_WRITE: update-in-place if same sender ────────────────────────
        // Design rationale (#195): LAST_WRITE channels model latest-state-only semantics
        // (e.g. a status heartbeat). The overwrite path intentionally skips:
        //   - Ledger write: recording every overwrite would flood the audit record; the
        //     channel's current state — not its history — is the relevant fact.
        //   - fanOut: external backends are not notified of overwrites; any backend
        //     requiring push updates must poll or subscribe to the channel separately.
        //     This may be revisited if real-time push semantics are added to LAST_WRITE.
        //   - Commitment tracking: LAST_WRITE channels are not obligation-creating speech
        //     acts; no COMMAND/QUERY semantics apply.
        if (ch != null && ch.semantic == ChannelSemantic.LAST_WRITE) {
            final Optional<Message> existingOpt = messageStore.findLastMessage(ch.id);
            if (existingOpt.isPresent()) {
                final Message last = existingOpt.get();
                if (last.sender.equals(dispatch.sender())) {
                    last.content = dispatch.content();
                    last.messageType = dispatch.type();
                    last.correlationId = dispatch.correlationId();
                    last.inReplyTo = dispatch.inReplyTo();
                    last.artefactRefs = dispatch.artefactRefs();
                    last.target = dispatch.target();
                    last.actorType = dispatch.actorType();
                    last.createdAt = Instant.now();
                    channelService.updateLastActivity(ch.id);
                    rateLimiter.recordSend(ch.id, dispatch.sender(),
                            ch.rateLimitPerChannel, ch.rateLimitPerInstance);
                    // No ledger write, fanOut, or commitment tracking for LAST_WRITE overwrite.
                    // subjectId/causedByEntryId from dispatch are not propagated — in-place update
                    // retains the original entry's lineage. See #195 for design rationale.
                    // parentReplyCount=0: semantically correct — LAST_WRITE overwrites do not
                    // create a new reply to any parent (no inReplyTo is added), so no parent's
                    // replyCount is incremented. Callers reading last.replyCount (how many messages
                    // replied to the LAST_WRITE message itself) must query messageService.findById().
                    return new DispatchResult(last.id, ch.id, last.sender,
                            last.messageType, last.correlationId, last.inReplyTo,
                            ArtefactRefParser.parse(last.artefactRefs), last.target,
                            null, null, null, 0);
                } else {
                    throw new IllegalStateException(
                            "LAST_WRITE channel '" + ch.name + "' already has a message from '"
                                    + last.sender + "'. Only the current writer may update this channel.");
                }
            }
        }

        // ── Normal insert ─────────────────────────────────────────────────────
        // Generate commitmentId before persisting so it lands on the message entity
        final UUID commitmentId = (dispatch.correlationId() != null &&
                (dispatch.type() == MessageType.COMMAND || dispatch.type() == MessageType.QUERY))
                ? UUID.randomUUID() : null;

        final Message message = new Message();
        message.channelId = dispatch.channelId();
        message.sender = dispatch.sender();
        message.messageType = dispatch.type();
        message.actorType = dispatch.actorType();
        message.content = dispatch.content();
        message.correlationId = dispatch.correlationId();
        message.inReplyTo = dispatch.inReplyTo();
        message.artefactRefs = dispatch.artefactRefs();
        message.target = dispatch.target();
        message.deadline = dispatch.deadline();
        message.commitmentId = commitmentId;
        messageStore.put(message);

        // Extract primitives BEFORE the REQUIRES_NEW boundary — no JPA entities cross it
        final Long messageId = message.id;
        final UUID storedCommitmentId = message.commitmentId;
        final Instant occurredAt = message.createdAt != null
                ? message.createdAt : Instant.now();

        // Commitment state machine
        if (dispatch.correlationId() != null) {
            switch (dispatch.type()) {
                case QUERY, COMMAND -> commitmentService.open(
                        storedCommitmentId,
                        dispatch.correlationId(), dispatch.channelId(), dispatch.type(),
                        dispatch.sender(), dispatch.target(), message.deadline);
                case STATUS -> commitmentService.acknowledge(dispatch.correlationId());
                case RESPONSE, DONE -> commitmentService.fulfill(dispatch.correlationId());
                case DECLINE -> commitmentService.decline(dispatch.correlationId());
                case FAILURE -> commitmentService.fail(dispatch.correlationId());
                case HANDOFF -> commitmentService.delegate(dispatch.correlationId(), dispatch.target());
                case EVENT -> { /* no commitment effect */ }
            }
        }

        int parentReplyCount = 0;
        if (dispatch.inReplyTo() != null) {
            final var parentMsg = messageStore.find(dispatch.inReplyTo());
            if (parentMsg.isPresent()) {
                parentMsg.get().replyCount++;
                parentReplyCount = parentMsg.get().replyCount;
            }
        }

        channelService.updateLastActivity(dispatch.channelId());

        // Ledger write (REQUIRES_NEW — commits independently; failure propagates and rolls back outer tx)
        final LedgerWriteOutcome ledgerOutcome =
                ledgerWriteService.record(dispatch, messageId, storedCommitmentId, occurredAt);

        // Observer fan-out (after ledger write for ordering consistency)
        MessageObserverDispatcher.dispatch(
                ch != null ? ch.name : null, dispatch.channelId(), message, observers.handles(), tsr);

        // ── Rate limit recording ──────────────────────────────────────────────
        if (ch != null && dispatch.type() != MessageType.EVENT) {
            rateLimiter.recordSend(ch.id, dispatch.sender(),
                    ch.rateLimitPerChannel, ch.rateLimitPerInstance);
        }

        // ── External backend fanOut ───────────────────────────────────────────
        if (ch != null) {
            try {
                channelGateway.fanOut(ch.id, ch.name, new OutboundMessage(
                        UUID.randomUUID(), dispatch.sender(), dispatch.type(), dispatch.content(),
                        dispatch.correlationId() != null
                                ? UUID.fromString(dispatch.correlationId()) : null,
                        dispatch.inReplyTo(),
                        dispatch.actorType()));
            } catch (final Exception e) {
                // fanOut failures are non-fatal — logged by ChannelGateway per-backend
            }
        }

        return new DispatchResult(
                messageId,
                dispatch.channelId(),
                dispatch.sender(),
                dispatch.type(),
                dispatch.correlationId(),
                dispatch.inReplyTo(),
                ArtefactRefParser.parse(dispatch.artefactRefs()),
                dispatch.target(),
                ledgerOutcome.entryId(),
                ledgerOutcome.subjectId(),
                ledgerOutcome.causedByEntryId(),
                parentReplyCount);
    }

    public Optional<Message> findById(final Long id) {
        return messageStore.find(id);
    }

    /**
     * Returns messages in channel posted after {@code afterId}, excluding EVENT type
     * (observer-only — not delivered to agent context).
     */
    public List<Message> pollAfter(final UUID channelId, final Long afterId, final int limit) {
        return pollAfter(channelId, afterId, limit, false);
    }

    /**
     * Returns messages in channel posted after {@code afterId}. If {@code includeEvents}
     * is true, EVENT messages are included (for read-only observer instances).
     */
    public List<Message> pollAfter(final UUID channelId, final Long afterId, final int limit,
            final boolean includeEvents) {
        final MessageQuery.Builder builder = MessageQuery.builder()
                .channelId(channelId)
                .afterId(afterId)
                .limit(limit);
        if (!includeEvents) {
            builder.excludeTypes(List.of(MessageType.EVENT));
        }
        return messageStore.scan(builder.build());
    }

    /**
     * Like {@link #pollAfter} but filters by sender in the query — avoids the
     * post-limit filtering bug where messages are lost when limit < total results.
     */
    public List<Message> pollAfterBySender(final UUID channelId, final Long afterId, final int limit,
            final String sender) {
        return pollAfterBySender(channelId, afterId, limit, sender, false);
    }

    /**
     * Like {@link #pollAfter(UUID, Long, int, boolean)} but also filters by sender.
     */
    public List<Message> pollAfterBySender(final UUID channelId, final Long afterId, final int limit,
            final String sender, final boolean includeEvents) {
        final MessageQuery.Builder builder = MessageQuery.builder()
                .channelId(channelId)
                .afterId(afterId)
                .limit(limit)
                .sender(sender);
        if (!includeEvents) {
            builder.excludeTypes(List.of(MessageType.EVENT));
        }
        return messageStore.scan(builder.build());
    }

    public Optional<Message> findByCorrelationId(final String correlationId) {
        return Message.find("correlationId", correlationId).firstResultOptional();
    }

    /** Returns all messages with the given correlation ID ordered by id ascending. */
    public List<Message> findAllByCorrelationId(final String correlationId) {
        return Message.<Message> find("correlationId = ?1 ORDER BY id ASC", correlationId).list();
    }

    /**
     * Find a RESPONSE message in the given channel with the given correlation ID.
     * Used by wait_for_reply to detect when a matching response has arrived.
     */
    @Transactional
    public Optional<Message> findResponseByCorrelationId(final UUID channelId, final String correlationId) {
        return Message.find(
                "channelId = ?1 AND messageType = ?2 AND correlationId = ?3",
                channelId, MessageType.RESPONSE, correlationId)
                .firstResultOptional();
    }

    /**
     * Find a DONE message in the given channel with the given correlation ID.
     * Used by wait_for_reply to detect when a COMMAND obligation has been discharged.
     */
    @Transactional
    public Optional<Message> findDoneByCorrelationId(final UUID channelId, final String correlationId) {
        return Message.find(
                "channelId = ?1 AND messageType = ?2 AND correlationId = ?3",
                channelId, MessageType.DONE, correlationId)
                .firstResultOptional();
    }
}
