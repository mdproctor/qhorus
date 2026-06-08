package io.casehub.qhorus.runtime.message;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.inject.Inject;

import jakarta.enterprise.inject.Instance;

import org.jboss.logging.Logger;

import io.casehub.ledger.runtime.service.TrustGateService;
import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.casehub.qhorus.api.gateway.MessageObserver;
import io.casehub.qhorus.api.gateway.OutboundMessage;
import io.casehub.qhorus.api.message.DispatchResult;
import io.casehub.qhorus.api.message.MessageDispatch;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.channel.AllowedWritersPolicy;
import io.casehub.qhorus.runtime.channel.Channel;
import io.casehub.qhorus.runtime.channel.RateLimiter;
import io.casehub.qhorus.runtime.config.QhorusConfig;
import io.casehub.qhorus.runtime.gateway.ChannelGateway;
import io.casehub.qhorus.runtime.instance.ReactiveInstanceService;
import io.casehub.qhorus.runtime.ledger.LedgerWriteOutcome;
import io.casehub.qhorus.runtime.ledger.ReactiveLedgerWriteService;
import io.casehub.qhorus.runtime.store.ReactiveChannelStore;
import io.casehub.qhorus.runtime.store.ReactiveCommitmentStore;
import io.casehub.qhorus.runtime.store.ReactiveMessageStore;
import io.casehub.qhorus.runtime.store.query.MessageQuery;
import io.quarkus.arc.properties.IfBuildProperty;
import io.quarkus.hibernate.reactive.panache.Panache;
import io.smallrye.mutiny.Uni;

/**
 * Reactive message dispatch service with full enforcement parity.
 *
 * <p>{@link #dispatch} mirrors the enforcement sequence of
 * {@link MessageService#dispatch} (the blocking gate):
 * <ol>
 *   <li>Channel load (within session scope)</li>
 *   <li>Paused check (sync)</li>
 *   <li>Writer ACL — guarded reactive fetch of capability tags, then sync policy check
 *       (skipped for EVENT)</li>
 *   <li>Rate limit check (sync, skipped for EVENT)</li>
 *   <li>Trust gate — COMMAND + named non-role target, via {@link TrustGateService#meetsThresholdAsync} (no ManagedExecutor).
 *       Note: the {@link io.casehub.qhorus.api.spi.ObligorTrustPolicy} SPI is bypassed in the reactive path
 *       — only the default threshold applies. Custom ObligorTrustPolicy beans are honoured in the blocking path
 *       only. See qhorus#235 for the reactive ObligorTrustPolicy SPI track.</li>
 *   <li>Type policy (sync)</li>
 *   <li>withTransaction: LAST_WRITE / normal insert / commitment open / reply count /
 *       channel activity / ledger write</li>
 *   <li>Post-tx: ReactiveCommitmentService state transitions</li>
 *   <li>Post-tx: observer dispatch, rate limit recording, fanOut</li>
 * </ol>
 *
 * <p>Observer dispatch is POST-commit — a correctness fix over the previous version
 * that fired observers inside the transaction. Refs #193.
 */
@IfBuildProperty(name = "casehub.qhorus.reactive.enabled", stringValue = "true")
@ApplicationScoped
public class ReactiveMessageService {

    private static final Logger LOG = Logger.getLogger(ReactiveMessageService.class);

    @Inject
    ReactiveMessageStore messageStore;

    @Inject
    ReactiveChannelStore reactiveChannelStore;

    @Inject
    ReactiveCommitmentStore commitmentStore;

    @Inject
    ReactiveCommitmentService reactiveCommitmentService;

    @Inject @Any
    Instance<MessageObserver> observers;

    @Inject
    ReactiveInstanceService reactiveInstanceService;

    @Inject
    AllowedWritersPolicy allowedWritersPolicy;

    @Inject
    RateLimiter rateLimiter;

    @Inject
    TrustGateService trustGateService;

    @Inject
    QhorusConfig config;

    @Inject
    MessageTypePolicy messageTypePolicy;

    @Inject
    ReactiveLedgerWriteService reactiveLedgerWriteService;

    @Inject
    ChannelGateway channelGateway;

    // ── TransactResult discriminated union (private inner types) ──────────────

    private sealed interface TransactResult permits OverwriteResult, FullResult {}
    private record OverwriteResult(DispatchResult result) implements TransactResult {}
    private record FullResult(DispatchContext ctx) implements TransactResult {}
    private record DispatchContext(
            long messageId,
            UUID commitmentId,
            Instant occurredAt,
            LedgerWriteOutcome ledgerOutcome,
            String channelName,
            int replyCount) {}

    /**
     * Dispatches a message to a channel via the reactive path with full enforcement parity.
     *
     * <p>Enforcement sequence mirrors {@link MessageService#dispatch} exactly.
     * See class Javadoc for the full phase breakdown.
     *
     * <p>The entire dispatch is wrapped in {@code Panache.withSession("qhorus", ...)} to
     * provide a Hibernate Reactive session context for the pre-transaction channel read and
     * ACL capability-tag lookup. The write phase uses {@code Panache.withTransaction} nested
     * inside. Refs #193.
     */
    public Uni<DispatchResult> dispatch(final MessageDispatch dispatch) {
        return Panache.withSession("qhorus", () -> doDispatch(dispatch));
    }

    /**
     * Internal dispatch logic — runs within a Hibernate Reactive session.
     */
    private Uni<DispatchResult> doDispatch(final MessageDispatch dispatch) {
        // Phase 1: Channel load (reactive)
        return reactiveChannelStore.find(dispatch.channelId())
                .flatMap(chOpt -> {
                    final Channel ch = chOpt.orElse(null);

                    // Phase 1a: Paused check (sync)
                    if (ch != null && ch.paused) {
                        throw new IllegalStateException(
                                "Channel '" + ch.name
                                        + "' is paused — send_message blocked. Use resume_channel to re-enable.");
                    }

                    // Phase 1b: ACL check — guarded reactive fetch
                    final Uni<Void> aclCheck;
                    if (ch != null && ch.allowedWriters != null && !ch.allowedWriters.isBlank()
                            && dispatch.type() != MessageType.EVENT) {
                        aclCheck = reactiveInstanceService.findCapabilityTagsForInstance(dispatch.sender())
                                .invoke(tags -> {
                                    final List<String> allTags = new ArrayList<>(tags);
                                    allTags.add("role:" + dispatch.actorType().name().toLowerCase());
                                    if (!allowedWritersPolicy.isAllowedWriter(
                                            dispatch.sender(), ch.allowedWriters, () -> allTags)) {
                                        throw new IllegalStateException(
                                                "Sender '" + dispatch.sender()
                                                        + "' is not permitted to write to channel '"
                                                        + ch.name
                                                        + "'. Channel has an allowed_writers ACL.");
                                    }
                                })
                                .replaceWithVoid();
                    } else {
                        aclCheck = Uni.createFrom().voidItem();
                    }

                    return aclCheck.map(ignored -> ch);
                })
                .invoke(ch -> {
                    // Phase 1c: Rate limit check (sync, skip EVENT)
                    if (ch != null && dispatch.type() != MessageType.EVENT) {
                        final String rateLimitError = rateLimiter.check(
                                ch.id, ch.name, dispatch.sender(),
                                ch.rateLimitPerChannel, ch.rateLimitPerInstance);
                        if (rateLimitError != null) {
                            throw new IllegalStateException(rateLimitError);
                        }
                    }
                })
                .flatMap(ch -> {
                    // Phase 1d: Trust gate (COMMAND + specific obligor only).
                    // Uses TrustGateService.meetsThresholdAsync() — no ManagedExecutor needed.
                    // Gate disabled when minObligorTrust <= 0. Refs casehubio/ledger#106, #213.
                    if (ch != null && dispatch.type() == MessageType.COMMAND
                            && dispatch.target() != null
                            && !dispatch.target().contains(":")) {
                        final double minTrust = config.commitment().minObligorTrust();
                        if (minTrust <= 0) {
                            return Uni.createFrom().item(ch);
                        }
                        return trustGateService.meetsThresholdAsync(dispatch.target(), minTrust)
                                .map(meets -> {
                                    if (!meets) {
                                        throw new IllegalStateException(
                                                "COMMAND rejected: obligor '" + dispatch.target()
                                                        + "' did not meet the trust threshold");
                                    }
                                    return ch;
                                });
                    }
                    return Uni.createFrom().item(ch);
                })
                .invoke(ch -> {
                    // Phase 1e: Type policy (sync)
                    if (ch != null) {
                        messageTypePolicy.validate(ch, dispatch.type());
                    }
                })
                // Phase 2: Transaction — LAST_WRITE / insert / commitment / reply / activity / ledger
                .flatMap(ch -> {
                    final UUID commitmentId = (dispatch.correlationId() != null
                            && (dispatch.type() == MessageType.COMMAND
                                    || dispatch.type() == MessageType.QUERY))
                            ? UUID.randomUUID() : null;

                    return Panache.<TransactResult>withTransaction("qhorus", () -> {
                        // ── LAST_WRITE: update-in-place if same sender ────────────────
                        if (ch != null && ch.semantic == ChannelSemantic.LAST_WRITE) {
                            return messageStore.findLastMessage(ch.id)
                                    .flatMap(existingOpt -> {
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
                                                return reactiveChannelStore.updateLastActivity(ch.id)
                                                        .map(ignored -> (TransactResult) new OverwriteResult(
                                                                new DispatchResult(
                                                                        last.id, ch.id, last.sender,
                                                                        last.messageType,
                                                                        last.correlationId,
                                                                        last.inReplyTo,
                                                                        ArtefactRefParser.parse(
                                                                                last.artefactRefs),
                                                                        last.target,
                                                                        null, null, null, 0)));
                                            } else {
                                                throw new IllegalStateException(
                                                        "LAST_WRITE channel '" + ch.name
                                                                + "' already has a message from '"
                                                                + last.sender
                                                                + "'. Only the current writer may update this channel.");
                                            }
                                        }
                                        // No existing message — fall through to normal insert
                                        return doNormalInsert(dispatch, ch, commitmentId);
                                    });
                        }

                        // ── Normal insert path ────────────────────────────────────────
                        return doNormalInsert(dispatch, ch, commitmentId);
                    })
                    // Phase 3: Pattern-match TransactResult
                    .flatMap(result -> {
                        if (result instanceof OverwriteResult or) {
                            // Record rate limit for overwrites, skip Phase 3 + 4
                            if (ch != null && dispatch.type() != MessageType.EVENT) {
                                rateLimiter.recordSend(ch.id, dispatch.sender(),
                                        ch.rateLimitPerChannel, ch.rateLimitPerInstance);
                            }
                            return Uni.createFrom().item(or.result());
                        }

                        final FullResult fr = (FullResult) result;
                        final DispatchContext ctx = fr.ctx();

                        // Phase 3: Commitment state transitions (post-commit)
                        return reactiveCommitmentService.updateState(dispatch, ctx.commitmentId())
                                .map(ignored -> {
                                    // Phase 4: Sync side effects (post-commit)

                                    // Observer dispatch (POST-commit — correctness fix)
                                    final Message syntheticMsg = new Message();
                                    syntheticMsg.id = ctx.messageId();
                                    syntheticMsg.channelId = dispatch.channelId();
                                    syntheticMsg.sender = dispatch.sender();
                                    syntheticMsg.messageType = dispatch.type();
                                    syntheticMsg.actorType = dispatch.actorType();
                                    syntheticMsg.content = dispatch.content();
                                    syntheticMsg.correlationId = dispatch.correlationId();
                                    syntheticMsg.inReplyTo = dispatch.inReplyTo();
                                    syntheticMsg.artefactRefs = dispatch.artefactRefs();
                                    syntheticMsg.target = dispatch.target();
                                    // Reactive path uses null TSR — observers are dispatched
                                    // synchronously. JTA TSR integration not yet wired for
                                    // reactive (Panache.withTransaction has no JTA TSR).
                                    MessageObserverDispatcher.dispatch(
                                            ctx.channelName(), dispatch.channelId(),
                                            syntheticMsg, observers.handles(), null);

                                    // Rate limit recording (skip EVENT)
                                    if (ch != null && dispatch.type() != MessageType.EVENT) {
                                        rateLimiter.recordSend(ch.id, dispatch.sender(),
                                                ch.rateLimitPerChannel, ch.rateLimitPerInstance);
                                    }

                                    // External backend fanOut
                                    if (ch != null) {
                                        try {
                                            channelGateway.fanOut(ch.id, ch.name, new OutboundMessage(
                                                    UUID.randomUUID(), dispatch.sender(),
                                                    dispatch.type(), dispatch.content(),
                                                    dispatch.correlationId() != null
                                                            ? UUID.fromString(dispatch.correlationId())
                                                            : null,
                                                    dispatch.inReplyTo(),
                                                    dispatch.actorType()));
                                        } catch (final Exception e) {
                                            // fanOut failures are non-fatal
                                        }
                                    }

                                    final LedgerWriteOutcome lo = ctx.ledgerOutcome();
                                    return new DispatchResult(
                                            ctx.messageId(),
                                            dispatch.channelId(),
                                            dispatch.sender(),
                                            dispatch.type(),
                                            dispatch.correlationId(),
                                            dispatch.inReplyTo(),
                                            ArtefactRefParser.parse(dispatch.artefactRefs()),
                                            dispatch.target(),
                                            lo.entryId(),
                                            lo.subjectId(),
                                            lo.causedByEntryId(),
                                            ctx.replyCount());
                                });
                    });
                });
    }

    /**
     * Normal insert path shared by LAST_WRITE fall-through and APPEND/COLLECT/BARRIER/EPHEMERAL.
     * Runs within the Panache.withTransaction block.
     */
    private Uni<TransactResult> doNormalInsert(final MessageDispatch dispatch,
            final Channel ch, final UUID commitmentId) {
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

        return messageStore.put(message).flatMap(m -> {
            final long messageId = m.id;
            final Instant occurredAt = m.createdAt != null ? m.createdAt : Instant.now();

            // Commitment open (COMMAND/QUERY with non-null correlationId) — same transaction
            final Uni<Void> commitmentOpen;
            if (commitmentId != null && dispatch.correlationId() != null) {
                final Commitment c = new Commitment();
                c.id = commitmentId;
                c.correlationId = dispatch.correlationId();
                c.channelId = dispatch.channelId();
                c.messageType = dispatch.type();
                c.requester = dispatch.sender();
                c.obligor = dispatch.target();
                c.expiresAt = dispatch.deadline();
                c.state = io.casehub.qhorus.api.message.CommitmentState.OPEN;
                commitmentOpen = commitmentStore.save(c).replaceWithVoid();
            } else {
                commitmentOpen = Uni.createFrom().voidItem();
            }

            // Reply count increment
            final Uni<Integer> replyCountUni;
            if (dispatch.inReplyTo() != null) {
                replyCountUni = messageStore.find(dispatch.inReplyTo())
                        .map(opt -> {
                            if (opt.isPresent()) {
                                opt.get().replyCount++;
                                return opt.get().replyCount;
                            }
                            return 0;
                        });
            } else {
                replyCountUni = Uni.createFrom().item(0);
            }

            // Chain: commitment open → reply count → channel activity → ledger write
            final String channelName = ch != null ? ch.name : null;
            return commitmentOpen
                    .flatMap(ignored -> replyCountUni)
                    .flatMap(replyCount -> {
                        final Uni<Void> activityUpdate = ch != null
                                ? reactiveChannelStore.updateLastActivity(ch.id)
                                : Uni.createFrom().voidItem();
                        return activityUpdate.map(ignored -> replyCount);
                    })
                    .flatMap(replyCount ->
                            reactiveLedgerWriteService.record(dispatch, messageId, commitmentId, occurredAt)
                                    .map(ledgerOutcome -> (TransactResult) new FullResult(
                                            new DispatchContext(
                                                    messageId, commitmentId, occurredAt,
                                                    ledgerOutcome, channelName, replyCount))));
        });
    }

    public Uni<Optional<Message>> findById(final Long id) {
        return Panache.withSession("qhorus", () -> messageStore.find(id));
    }

    /**
     * Returns messages in channel posted after {@code afterId}, excluding EVENT type.
     */
    public Uni<List<Message>> pollAfter(final UUID channelId, final Long afterId, final int limit) {
        return Panache.withSession("qhorus", () -> messageStore.scan(
                MessageQuery.builder()
                        .channelId(channelId)
                        .afterId(afterId)
                        .limit(limit)
                        .excludeTypes(List.of(MessageType.EVENT))
                        .build()));
    }

    /**
     * Like {@link #pollAfter} but filters by sender in the query.
     */
    public Uni<List<Message>> pollAfterBySender(final UUID channelId, final Long afterId,
            final int limit, final String sender) {
        return Panache.withSession("qhorus", () -> messageStore.scan(
                MessageQuery.builder()
                        .channelId(channelId)
                        .afterId(afterId)
                        .limit(limit)
                        .excludeTypes(List.of(MessageType.EVENT))
                        .sender(sender)
                        .build()));
    }
}
