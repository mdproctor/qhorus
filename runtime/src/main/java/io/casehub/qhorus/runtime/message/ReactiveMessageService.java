package io.casehub.qhorus.runtime.message;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.inject.Inject;

import jakarta.enterprise.inject.Instance;

import org.jboss.logging.Logger;

import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.casehub.qhorus.api.gateway.ChannelActivityBroadcaster;
import io.casehub.qhorus.api.gateway.ChannelActivityBroadcaster.ChannelActivityEvent;
import io.casehub.qhorus.api.gateway.MessageObserver;
import io.casehub.qhorus.api.gateway.OutboundMessage;
import io.casehub.qhorus.api.message.DispatchResult;
import io.casehub.qhorus.api.message.Commitment;
import io.casehub.qhorus.api.message.Message;
import io.casehub.qhorus.api.message.MessageDispatch;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.api.message.ReactiveMessageDispatcher;
import io.casehub.qhorus.api.spi.ObligorTrustContext;
import io.casehub.qhorus.api.spi.ObligorTrustPolicy;
import io.casehub.qhorus.runtime.channel.AllowedWritersPolicy;
import io.casehub.qhorus.api.channel.Channel;
import io.casehub.qhorus.runtime.channel.RateLimiter;
import io.casehub.qhorus.runtime.config.QhorusConfig;
import io.casehub.qhorus.runtime.gateway.ChannelGateway;
import io.casehub.qhorus.runtime.gateway.DeliverySignalQueue;
import io.casehub.qhorus.runtime.instance.ReactiveInstanceService;
import io.casehub.qhorus.runtime.ledger.LedgerWriteOutcome;
import io.casehub.qhorus.runtime.ledger.ReactiveLedgerWriteService;
import io.casehub.qhorus.api.store.ReactiveChannelStore;
import io.casehub.qhorus.api.store.ReactiveCommitmentStore;
import io.casehub.qhorus.api.store.ReactiveMessageStore;
import io.casehub.qhorus.api.store.query.MessageQuery;
import io.quarkus.arc.properties.IfBuildProperty;
import io.quarkus.hibernate.reactive.panache.Panache;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;

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
 *   <li>Trust gate — COMMAND + named non-role target, via {@link ObligorTrustPolicy#permits}
 *       on a worker thread. Custom {@link ObligorTrustPolicy} beans are honoured in both blocking
 *       and reactive paths. {@link DefaultObligorTrustPolicy} delegates internally to the ledger's
 *       trust threshold. Refs qhorus#235.</li>
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
public class ReactiveMessageService implements ReactiveMessageDispatcher {

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
    ObligorTrustPolicy obligorTrustPolicy;

    @Inject
    QhorusConfig config;

    @Inject
    MessageTypePolicy messageTypePolicy;

    @Inject
    ReactiveLedgerWriteService reactiveLedgerWriteService;

    @Inject
    ChannelGateway channelGateway;

    @Inject
    DeliverySignalQueue deliverySignalQueue;

    @Inject
    ChannelActivityBroadcaster broadcaster;

    @Inject
    Instance<io.opentelemetry.api.trace.Tracer> tracerInstance;

    @Inject
    io.casehub.qhorus.runtime.config.QhorusTracingConfig tracingConfig;

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
        final AtomicReference<List<String>> advisoriesRef = new AtomicReference<>(List.of());

        // ── Start tracing span ────────────────────────────────────────────────
        io.opentelemetry.api.trace.Span span = null;
        io.opentelemetry.context.Scope scope = null;
        if (tracingConfig.enabled() && tracingConfig.dispatch() && tracerInstance.isResolvable()) {
            span = tracerInstance.get().spanBuilder("qhorus.dispatch")
                    .setSpanKind(io.opentelemetry.api.trace.SpanKind.INTERNAL)
                    .startSpan();
            scope = span.makeCurrent();
        }
        final io.opentelemetry.api.trace.Span finalSpan = span;
        final io.opentelemetry.context.Scope finalScope = scope;
        final AtomicReference<String> effectiveTenancyIdRef = new AtomicReference<>(null);

        // Phase 1: Channel load (reactive)
        return reactiveChannelStore.find(dispatch.channelId())
                .flatMap(chOpt -> {
                    final Channel ch = chOpt.orElse(null);

                    // Set span attributes: message-level first, then channel-level
                    if (finalSpan != null) {
                        finalSpan.setAttribute("qhorus.message.type", dispatch.type().name());
                        finalSpan.setAttribute("qhorus.message.sender", dispatch.sender());
                        finalSpan.setAttribute("qhorus.actor.type", dispatch.actorType().name());
                        if (dispatch.correlationId() != null) {
                            finalSpan.setAttribute("qhorus.message.correlation_id", dispatch.correlationId());
                        }
                        if (dispatch.target() != null) {
                            finalSpan.setAttribute("qhorus.message.target", dispatch.target());
                        }

                        if (ch != null) {
                            final String effectiveTenancyId = dispatch.tenancyId() != null
                                    ? dispatch.tenancyId()
                                    : ch.tenancyId();
                            effectiveTenancyIdRef.set(effectiveTenancyId);
                            finalSpan.setAttribute("qhorus.channel.id", ch.id().toString());
                            finalSpan.setAttribute("qhorus.channel.name", ch.name());
                            finalSpan.setAttribute("qhorus.channel.semantic", ch.semantic().name());
                            finalSpan.setAttribute("qhorus.tenancy.id", effectiveTenancyId);
                        }
                    }

                    // Phase 1a: Paused check (sync)
                    if (ch != null && ch.paused()) {
                        throw new IllegalStateException(
                                "Channel '" + ch.name()
                                        + "' is paused — send_message blocked. Use resume_channel to re-enable.");
                    }

                    // Phase 1b: ACL check — guarded reactive fetch
                    final Uni<Void> aclCheck;
                    if (ch != null && ch.allowedWriters() != null && !ch.allowedWriters().isEmpty()
                            && dispatch.type() != MessageType.EVENT) {
                        aclCheck = reactiveInstanceService.findCapabilityTagsForInstance(dispatch.sender())
                                .invoke(tags -> {
                                    final List<String> allTags = new ArrayList<>(tags);
                                    allTags.add("role:" + dispatch.actorType().name().toLowerCase());
                                    if (!allowedWritersPolicy.isAllowedWriter(
                                            dispatch.sender(), ch.allowedWriters(), () -> allTags)) {
                                        throw new IllegalStateException(
                                                "Sender '" + dispatch.sender()
                                                        + "' is not permitted to write to channel '"
                                                        + ch.name()
                                                        + "'. Channel has an allowed_writers ACL.");
                                    }
                                    if (finalSpan != null) {
                                        finalSpan.addEvent("qhorus.enforcement.acl");
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
                                ch.id(), ch.name(), dispatch.sender(),
                                ch.rateLimitPerChannel(), ch.rateLimitPerInstance());
                        if (rateLimitError != null) {
                            throw new IllegalStateException(rateLimitError);
                        }
                        if (finalSpan != null) {
                            finalSpan.addEvent("qhorus.enforcement.rate_limit");
                        }
                    }
                })
                .flatMap(ch -> {
                    // Phase 1d: Trust gate (COMMAND + specific obligor only).
                    // Delegates to ObligorTrustPolicy.permits() on a worker thread — honours custom
                    // policy beans just as the blocking path does. DefaultObligorTrustPolicy calls
                    // trustGateService.meetsThreshold() (blocking) which is safe on the worker pool.
                    // Gate skipped when minObligorTrust <= 0. Refs qhorus#235, ledger#106, #213.
                    if (ch != null && dispatch.type() == MessageType.COMMAND
                            && dispatch.target() != null
                            && !dispatch.target().contains(":")) {
                        final double minTrust = config.commitment().minObligorTrust();
                        if (minTrust <= 0) {
                            return Uni.createFrom().item(ch);
                        }
                        final ObligorTrustContext trustCtx =
                                new ObligorTrustContext(dispatch.target(), ch.id(), ch.name());
                        return Uni.createFrom().item(() -> obligorTrustPolicy.permits(trustCtx))
                                .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                                .map(permitted -> {
                                    if (!permitted) {
                                        throw new IllegalStateException(
                                                "COMMAND rejected: obligor '" + dispatch.target()
                                                        + "' did not meet the trust threshold");
                                    }
                                    if (finalSpan != null) {
                                        finalSpan.addEvent("qhorus.enforcement.trust");
                                    }
                                    return ch;
                                });
                    }
                    return Uni.createFrom().item(ch);
                })
                .invoke(ch -> {
                    // Phase 1e: Type policy (sync)
                    if (ch != null) {
                        messageTypePolicy.validate(ch, dispatch.type()); // hard gate
                        final String adv = messageTypePolicy.advisory(ch, dispatch.type());
                        if (adv != null) {
                            LOG.warn(adv);
                            advisoriesRef.set(List.of(adv));
                        }
                        if (finalSpan != null) {
                            finalSpan.addEvent("qhorus.enforcement.type_policy");
                        }
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
                        if (ch != null && ch.semantic() == ChannelSemantic.LAST_WRITE) {
                            return messageStore.findLastMessage(ch.id())
                                    .flatMap(existingOpt -> {
                                        if (existingOpt.isPresent()) {
                                            final Message last = existingOpt.get();
                                            if (last.sender().equals(dispatch.sender())) {
                                                Message updated = last.toBuilder()
                                                        .content(dispatch.content())
                                                        .messageType(dispatch.type())
                                                        .correlationId(dispatch.correlationId())
                                                        .inReplyTo(dispatch.inReplyTo())
                                                        .artefactRefs(ArtefactRefParser.parse(dispatch.artefactRefs()))
                                                        .target(dispatch.target())
                                                        .topic(dispatch.topic())
                                                        .actorType(dispatch.actorType())
                                                        .createdAt(Instant.now())
                                                        .version(last.version() + 1)
                                                        .build();
                                                return messageStore.put(updated)
                                                        .flatMap(saved -> reactiveChannelStore.updateLastActivity(ch.id(), ch.tenancyId())
                                                        .map(ignored -> (TransactResult) new OverwriteResult(
                                                                new DispatchResult(
                                                                        saved.id(), ch.id(), saved.sender(),
                                                                        saved.messageType(),
                                                                        saved.correlationId(),
                                                                        saved.inReplyTo(),
                                                                        saved.artefactRefs(),
                                                                        saved.target(),
                                                                        null, null, null, 0,
                                                                        advisoriesRef.get()))));
                                            } else {
                                                throw new IllegalStateException(
                                                        "LAST_WRITE channel '" + ch.name()
                                                                + "' already has a message from '"
                                                                + last.sender()
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
                    // LAST_WRITE OverwriteResult: fanOut + broadcast fire, but MessageObserverDispatcher
                    // is intentionally excluded — an overwrite is a content update, not a new message event.
                    .flatMap(result -> {
                        if (result instanceof OverwriteResult or) {
                            // Record rate limit for overwrites, skip Phase 3 + 4
                            if (ch != null && dispatch.type() != MessageType.EVENT) {
                                rateLimiter.recordSend(ch.id(), dispatch.sender(),
                                        ch.rateLimitPerChannel(), ch.rateLimitPerInstance());
                            }
                            if (ch != null) {
                                try {
                                    channelGateway.fanOut(ch.id(), ch.name(), new OutboundMessage(
                                            UUID.randomUUID(), dispatch.sender(), dispatch.type(),
                                            dispatch.content(),
                                            dispatch.correlationId(),
                                            dispatch.inReplyTo(),
                                            dispatch.actorType()));
                                } catch (final Exception e) {
                                    // fanOut failures are non-fatal
                                }
                                deliverySignalQueue.signal(ch.id());
                                broadcaster.broadcast(new ChannelActivityEvent(
                                        ch.id(), ch.name(), or.result().messageId()));
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
                                    final Message syntheticMsg = Message.builder()
                                            .id(ctx.messageId())
                                            .channelId(dispatch.channelId())
                                            .sender(dispatch.sender())
                                            .messageType(dispatch.type())
                                            .actorType(dispatch.actorType())
                                            .content(dispatch.content())
                                            .correlationId(dispatch.correlationId())
                                            .inReplyTo(dispatch.inReplyTo())
                                            .artefactRefs(ArtefactRefParser.parse(dispatch.artefactRefs()))
                                            .target(dispatch.target())
                                            .topic(dispatch.topic())
                                            .createdAt(ctx.occurredAt())
                                            .tenancyId(dispatch.tenancyId())
                                            .build();
                                    MessageObserverDispatcher.dispatch(
                                            ctx.channelName(), dispatch.channelId(),
                                            syntheticMsg.tenancyId(),
                                            syntheticMsg, observers.handles(), null);

                                    if (finalSpan != null) {
                                        finalSpan.addEvent("qhorus.observer.dispatch");
                                    }

                                    // Rate limit recording (skip EVENT)
                                    if (ch != null && dispatch.type() != MessageType.EVENT) {
                                        rateLimiter.recordSend(ch.id(), dispatch.sender(),
                                                ch.rateLimitPerChannel(), ch.rateLimitPerInstance());
                                    }

                                    // External backend fanOut
                                    if (ch != null) {
                                        try {
                                            final boolean hasTracked = channelGateway.fanOut(
                                                    ch.id(), ch.name(), new OutboundMessage(
                                                    UUID.randomUUID(), dispatch.sender(),
                                                    dispatch.type(), dispatch.content(),
                                                    dispatch.correlationId(),
                                                    dispatch.inReplyTo(),
                                                    dispatch.actorType()));

                                            // Post-commit delivery signal: this runs after the message-insert
                                            // transaction has committed, so the pump will see the committed message.
                                            if (hasTracked) {
                                                deliverySignalQueue.signal(ch.id());
                                            }
                                        } catch (final Exception e) {
                                            // fanOut failures are non-fatal
                                        }

                                        broadcaster.broadcast(new ChannelActivityEvent(
                                                ch.id(), ch.name(), ctx.messageId()));
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
                                            ctx.replyCount(),
                                            advisoriesRef.get());
                                });
                    });
                })
                // ── Span lifecycle: onFailure before onTermination ──────────────
                .onFailure().invoke(t -> {
                    if (finalSpan != null) {
                        finalSpan.setStatus(io.opentelemetry.api.trace.StatusCode.ERROR);
                        finalSpan.recordException(t);
                    }
                })
                .onTermination().invoke(() -> {
                    if (finalScope != null) finalScope.close();
                    if (finalSpan != null) finalSpan.end();
                });
    }

    /**
     * Normal insert path shared by LAST_WRITE fall-through and APPEND/COLLECT/BARRIER/EPHEMERAL.
     * Runs within the Panache.withTransaction block.
     */
    private Uni<TransactResult> doNormalInsert(final MessageDispatch dispatch,
                                               final Channel ch, final UUID commitmentId) {
        final Message message = Message.builder()
                .channelId(dispatch.channelId())
                .sender(dispatch.sender())
                .messageType(dispatch.type())
                .actorType(dispatch.actorType())
                .content(dispatch.content())
                .correlationId(dispatch.correlationId())
                .inReplyTo(dispatch.inReplyTo())
                .artefactRefs(ArtefactRefParser.parse(dispatch.artefactRefs()))
                .target(dispatch.target())
                .topic(dispatch.topic())
                .deadline(dispatch.deadline())
                .commitmentId(commitmentId)
                .tenancyId(dispatch.tenancyId())
                .build();

        return messageStore.put(message).flatMap(m -> {
            final long messageId = m.id();
            final Instant occurredAt = m.createdAt() != null ? m.createdAt() : Instant.now();

            final Uni<Void> commitmentOpen;
            if (commitmentId != null && dispatch.correlationId() != null) {
                final io.casehub.qhorus.api.message.Commitment c = io.casehub.qhorus.api.message.Commitment.builder()
                        .id(commitmentId)
                        .correlationId(dispatch.correlationId())
                        .channelId(dispatch.channelId())
                        .messageType(dispatch.type())
                        .requester(dispatch.sender())
                        .obligor(dispatch.target())
                        .expiresAt(dispatch.deadline())
                        .state(io.casehub.qhorus.api.message.CommitmentState.OPEN)
                        .build();
                commitmentOpen = commitmentStore.save(c).replaceWithVoid();
            } else {
                commitmentOpen = Uni.createFrom().voidItem();
            }

            // Reply count increment
            final Uni<Integer> replyCountUni;
            if (dispatch.inReplyTo() != null) {
                replyCountUni = messageStore.find(dispatch.inReplyTo())
                        .flatMap(opt -> {
                            if (opt.isPresent()) {
                                Message parent = opt.get();
                                Message updatedParent = parent.toBuilder()
                                        .replyCount(parent.replyCount() + 1).build();
                                return messageStore.put(updatedParent)
                                        .map(saved -> saved.replyCount());
                            }
                            return Uni.createFrom().item(0);
                        });
            } else {
                replyCountUni = Uni.createFrom().item(0);
            }

            // Chain: commitment open → reply count → channel activity → ledger write
            final String channelName = ch != null ? ch.name() : null;
            return commitmentOpen
                    .flatMap(ignored -> replyCountUni)
                    .flatMap(replyCount -> {
                        final Uni<Void> activityUpdate = ch != null
                                ? reactiveChannelStore.updateLastActivity(ch.id(), ch.tenancyId())
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
