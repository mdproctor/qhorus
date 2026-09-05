package io.casehub.qhorus.runtime.message;

import io.casehub.qhorus.api.message.Commitment;
import io.casehub.qhorus.api.message.CommitmentDeclinedEvent;
import io.casehub.qhorus.api.message.CommitmentExpiredEvent;
import io.casehub.qhorus.api.message.CommitmentState;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.api.store.CommitmentStore;
import io.casehub.qhorus.runtime.config.QhorusTracingConfig;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class CommitmentService {

    private static final Logger LOG = Logger.getLogger(CommitmentService.class);

    @Inject
    CommitmentStore store;

    @Inject
    Event<CommitmentDeclinedEvent> declinedEvents;

    @Inject
    Event<CommitmentExpiredEvent> expiredEvents;

    @Inject
    Instance<Tracer> tracerInstance;

    @Inject
    QhorusTracingConfig tracingConfig;


    @Transactional
    public Commitment open(UUID commitmentId, String correlationId, UUID channelId,
                           MessageType type, String requester, String obligor, Instant expiresAt) {
        return open(commitmentId, correlationId, channelId, type, requester, obligor,
                    expiresAt, null, null);
    }

    @Transactional
    public Commitment open(UUID commitmentId, String correlationId, UUID channelId,
                           MessageType type, String requester, String obligor,
                           Instant expiresAt, String tenancyId, String capabilityTag) {
        Span span = null;
        if (tracingConfig.enabled() && tracingConfig.commitments() && tracerInstance.isResolvable()) {
            span = tracerInstance.get().spanBuilder("qhorus.commitment.open")
                                 .setSpanKind(SpanKind.INTERNAL)
                                 .startSpan();
            span.setAttribute("qhorus.commitment.id", commitmentId.toString());
            span.setAttribute("qhorus.commitment.correlation_id", correlationId);
            span.setAttribute("qhorus.commitment.to_state", "OPEN");
            span.setAttribute("qhorus.commitment.obligor", obligor != null ? obligor : "");
            span.setAttribute("qhorus.channel.id", channelId.toString());
        }
        try {
            Commitment c = Commitment.builder()
                                     .id(commitmentId)
                                     .correlationId(correlationId)
                                     .channelId(channelId)
                                     .messageType(type)
                                     .requester(requester)
                                     .obligor(obligor)
                                     .expiresAt(expiresAt)
                                     .tenancyId(tenancyId)
                                     .capabilityTag(capabilityTag)
                                     .state(CommitmentState.OPEN)
                                     .build();
            return store.save(c);
        } catch (Exception e) {
            if (span != null) {
                span.setStatus(StatusCode.ERROR);
                span.recordException(e);
            }
            throw e;
        } finally {
            if (span != null) {span.end();}
        }
    }

    @Transactional
    public Optional<Commitment> acknowledge(String correlationId) {
        if (correlationId == null || correlationId.isBlank()) return Optional.empty();
        Span span = null;
        if (tracingConfig.enabled() && tracingConfig.commitments() && tracerInstance.isResolvable()) {
            span = tracerInstance.get().spanBuilder("qhorus.commitment.acknowledge")
                    .setSpanKind(SpanKind.INTERNAL)
                    .startSpan();
        }
        final Span finalSpan = span;
        try {
            return store.findByCorrelationId(correlationId)
                    .filter(c -> c.state().isActive())
                    .map(c -> {
                        if (finalSpan != null) {
                            finalSpan.setAttribute("qhorus.commitment.id", c.id().toString());
                            finalSpan.setAttribute("qhorus.commitment.correlation_id", correlationId);
                            finalSpan.setAttribute("qhorus.commitment.from_state", c.state().name());
                            finalSpan.setAttribute("qhorus.commitment.to_state", "ACKNOWLEDGED");
                            finalSpan.setAttribute("qhorus.commitment.obligor", c.obligor() != null ? c.obligor() : "");
                            finalSpan.setAttribute("qhorus.channel.id", c.channelId().toString());
                        }
                        return store.save(c.toBuilder()
                                .state(CommitmentState.ACKNOWLEDGED)
                                .acknowledgedAt(c.acknowledgedAt() == null ? Instant.now() : c.acknowledgedAt())
                                .build());
                    });
        } catch (Exception e) {
            if (finalSpan != null) {
                finalSpan.setStatus(StatusCode.ERROR);
                finalSpan.recordException(e);
            }
            throw e;
        } finally {
            if (finalSpan != null) finalSpan.end();
        }
    }

    @Transactional
    public Optional<Commitment> fulfill(String correlationId) {
        if (correlationId == null || correlationId.isBlank()) return Optional.empty();
        Span span = null;
        if (tracingConfig.enabled() && tracingConfig.commitments() && tracerInstance.isResolvable()) {
            span = tracerInstance.get().spanBuilder("qhorus.commitment.fulfill")
                    .setSpanKind(SpanKind.INTERNAL)
                    .startSpan();
        }
        final Span finalSpan = span;
        try {
            return store.findByCorrelationId(correlationId)
                    .filter(c -> c.state().isActive())
                    .map(c -> {
                        if (finalSpan != null) {
                            finalSpan.setAttribute("qhorus.commitment.id", c.id().toString());
                            finalSpan.setAttribute("qhorus.commitment.correlation_id", correlationId);
                            finalSpan.setAttribute("qhorus.commitment.from_state", c.state().name());
                            finalSpan.setAttribute("qhorus.commitment.to_state", "FULFILLED");
                            finalSpan.setAttribute("qhorus.commitment.obligor", c.obligor() != null ? c.obligor() : "");
                            finalSpan.setAttribute("qhorus.channel.id", c.channelId().toString());
                        }
                        return store.save(c.toBuilder()
                                .state(CommitmentState.FULFILLED)
                                .resolvedAt(Instant.now())
                                .build());
                    });
        } catch (Exception e) {
            if (finalSpan != null) {
                finalSpan.setStatus(StatusCode.ERROR);
                finalSpan.recordException(e);
            }
            throw e;
        } finally {
            if (finalSpan != null) finalSpan.end();
        }
    }

    @Transactional
    public Optional<Commitment> decline(String correlationId) {
        if (correlationId == null || correlationId.isBlank()) return Optional.empty();
        Span span = null;
        if (tracingConfig.enabled() && tracingConfig.commitments() && tracerInstance.isResolvable()) {
            span = tracerInstance.get().spanBuilder("qhorus.commitment.decline")
                    .setSpanKind(SpanKind.INTERNAL)
                    .startSpan();
        }
        final Span finalSpan = span;
        try {
            return store.findByCorrelationId(correlationId)
                    .filter(c -> c.state().isActive())
                    .map(c -> {
                        if (finalSpan != null) {
                            finalSpan.setAttribute("qhorus.commitment.id", c.id().toString());
                            finalSpan.setAttribute("qhorus.commitment.correlation_id", correlationId);
                            finalSpan.setAttribute("qhorus.commitment.from_state", c.state().name());
                            finalSpan.setAttribute("qhorus.commitment.to_state", "DECLINED");
                            finalSpan.setAttribute("qhorus.commitment.obligor", c.obligor() != null ? c.obligor() : "");
                            finalSpan.setAttribute("qhorus.channel.id", c.channelId().toString());
                        }
                        Commitment saved = store.save(c.toBuilder()
                                .state(CommitmentState.DECLINED)
                                .resolvedAt(Instant.now())
                                .build());
                        declinedEvents.fire(new CommitmentDeclinedEvent(
                                saved.id(), saved.correlationId(), saved.channelId(),
                                saved.obligor(), saved.requester()));
                        return saved;
                    });
        } catch (Exception e) {
            if (finalSpan != null) {
                finalSpan.setStatus(StatusCode.ERROR);
                finalSpan.recordException(e);
            }
            throw e;
        } finally {
            if (finalSpan != null) finalSpan.end();
        }
    }

    @Transactional
    public Optional<Commitment> fail(String correlationId) {
        if (correlationId == null || correlationId.isBlank()) return Optional.empty();
        Span span = null;
        if (tracingConfig.enabled() && tracingConfig.commitments() && tracerInstance.isResolvable()) {
            span = tracerInstance.get().spanBuilder("qhorus.commitment.fail")
                    .setSpanKind(SpanKind.INTERNAL)
                    .startSpan();
        }
        final Span finalSpan = span;
        try {
            return store.findByCorrelationId(correlationId)
                    .filter(c -> c.state().isActive())
                    .map(c -> {
                        if (finalSpan != null) {
                            finalSpan.setAttribute("qhorus.commitment.id", c.id().toString());
                            finalSpan.setAttribute("qhorus.commitment.correlation_id", correlationId);
                            finalSpan.setAttribute("qhorus.commitment.from_state", c.state().name());
                            finalSpan.setAttribute("qhorus.commitment.to_state", "FAILED");
                            finalSpan.setAttribute("qhorus.commitment.obligor", c.obligor() != null ? c.obligor() : "");
                            finalSpan.setAttribute("qhorus.channel.id", c.channelId().toString());
                        }
                        return store.save(c.toBuilder()
                                .state(CommitmentState.FAILED)
                                .resolvedAt(Instant.now())
                                .build());
                    });
        } catch (Exception e) {
            if (finalSpan != null) {
                finalSpan.setStatus(StatusCode.ERROR);
                finalSpan.recordException(e);
            }
            throw e;
        } finally {
            if (finalSpan != null) finalSpan.end();
        }
    }

    @Transactional
    public Optional<Commitment> delegate(String correlationId, String delegatedTo) {
        if (correlationId == null || correlationId.isBlank()) return Optional.empty();
        Span span = null;
        if (tracingConfig.enabled() && tracingConfig.commitments() && tracerInstance.isResolvable()) {
            span = tracerInstance.get().spanBuilder("qhorus.commitment.delegate")
                    .setSpanKind(SpanKind.INTERNAL)
                    .startSpan();
        }
        final Span finalSpan = span;
        try {
            return store.findByCorrelationId(correlationId)
                    .filter(c -> c.state().isActive())
                    .map(c -> {
                        if (finalSpan != null) {
                            finalSpan.setAttribute("qhorus.commitment.id", c.id().toString());
                            finalSpan.setAttribute("qhorus.commitment.correlation_id", correlationId);
                            finalSpan.setAttribute("qhorus.commitment.from_state", c.state().name());
                            finalSpan.setAttribute("qhorus.commitment.to_state", "DELEGATED");
                            finalSpan.setAttribute("qhorus.commitment.obligor", c.obligor() != null ? c.obligor() : "");
                            finalSpan.setAttribute("qhorus.channel.id", c.channelId().toString());
                        }
                        Commitment delegated = store.save(c.toBuilder()
                                .state(CommitmentState.DELEGATED)
                                .delegatedTo(delegatedTo)
                                .resolvedAt(Instant.now())
                                .build());
                        Commitment child = Commitment.builder()
                                .correlationId(correlationId)
                                .channelId(c.channelId())
                                .messageType(c.messageType())
                                .requester(c.requester())
                                .obligor(delegatedTo)
                                .expiresAt(c.expiresAt())
                                .state(CommitmentState.OPEN)
                                .parentCommitmentId(c.id())
                                .tenancyId(c.tenancyId())
                                .capabilityTag(c.capabilityTag())
                                .build();
                        store.save(child);
                        return delegated;
                    });
        } catch (Exception e) {
            if (finalSpan != null) {
                finalSpan.setStatus(StatusCode.ERROR);
                finalSpan.recordException(e);
            }
            throw e;
        } finally {
            if (finalSpan != null) finalSpan.end();
        }
    }

    @Transactional
    public int expireOverdue() {
        Span span = null;
        if (tracingConfig.enabled() && tracingConfig.commitments() && tracerInstance.isResolvable()) {
            span = tracerInstance.get().spanBuilder("qhorus.commitment.expire_overdue")
                    .setSpanKind(SpanKind.INTERNAL)
                    .setNoParent()
                    .startSpan();
        }
        final Span finalSpan = span;
        try {
            List<Commitment> overdue = store.findExpiredBefore(Instant.now());
            List<CommitmentExpiredEvent> toFire = new ArrayList<>(overdue.size());
            overdue.forEach(c -> {
                if (finalSpan != null) {
                    finalSpan.addEvent("qhorus.commitment.expired",
                            Attributes.of(
                                    AttributeKey.stringKey("commitment_id"), c.id().toString(),
                                    AttributeKey.stringKey("correlation_id"), c.correlationId(),
                                    AttributeKey.stringKey("obligor"), c.obligor() != null ? c.obligor() : ""));
                }
                store.save(c.toBuilder()
                        .state(CommitmentState.EXPIRED)
                        .resolvedAt(Instant.now())
                        .build());
                toFire.add(new CommitmentExpiredEvent(
                        c.id(), c.correlationId(), c.channelId(), c.obligor(), c.requester(), c.expiresAt()));
            });
            if (finalSpan != null) {
                finalSpan.setAttribute("qhorus.commitment.expired_count", overdue.size());
            }
            toFire.forEach(event -> {
                try {
                    expiredEvents.fire(event);
                } catch (Exception e) {
                    LOG.warnf(e, "CommitmentExpiredEvent observer failed for commitment %s — continuing", event.commitmentId());
                }
            });
            return overdue.size();
        } catch (Exception e) {
            if (finalSpan != null) {
                finalSpan.setStatus(StatusCode.ERROR);
                finalSpan.recordException(e);
            }
            throw e;
        } finally {
            if (finalSpan != null) finalSpan.end();
        }
    }

    @Transactional
    public Optional<Commitment> extendDeadline(String correlationId, Instant newDeadline) {
        if (correlationId == null || correlationId.isBlank()) return Optional.empty();
        Span span = null;
        if (tracingConfig.enabled() && tracingConfig.commitments() && tracerInstance.isResolvable()) {
            span = tracerInstance.get().spanBuilder("qhorus.commitment.extend_deadline")
                    .setSpanKind(SpanKind.INTERNAL)
                    .startSpan();
        }
        final Span finalSpan = span;
        try {
            return store.findByCorrelationId(correlationId)
                    .filter(c -> c.state().isActive())
                    .map(c -> {
                        if (finalSpan != null) {
                            finalSpan.setAttribute("qhorus.commitment.id", c.id().toString());
                            finalSpan.setAttribute("qhorus.commitment.correlation_id", correlationId);
                            finalSpan.setAttribute("qhorus.commitment.from_state", c.state().name());
                            finalSpan.setAttribute("qhorus.commitment.to_state", c.state().name());
                            finalSpan.setAttribute("qhorus.commitment.obligor", c.obligor() != null ? c.obligor() : "");
                            finalSpan.setAttribute("qhorus.channel.id", c.channelId().toString());
                            finalSpan.setAttribute("qhorus.commitment.new_deadline", newDeadline.toString());
                        }
                        return store.save(c.toBuilder().expiresAt(newDeadline).build());
                    });
        } catch (Exception e) {
            if (finalSpan != null) {
                finalSpan.setStatus(StatusCode.ERROR);
                finalSpan.recordException(e);
            }
            throw e;
        } finally {
            if (finalSpan != null) finalSpan.end();
        }
    }

    public Optional<Commitment> findByCorrelationId(String correlationId) {
        if (correlationId == null || correlationId.isBlank()) return Optional.empty();
        return store.findByCorrelationId(correlationId);
    }

    @Transactional
    public int expireByChannel(UUID channelId) {
        List<Commitment> active = store.findOpenByChannelId(channelId);
        List<CommitmentExpiredEvent> toFire = new ArrayList<>(active.size());
        Instant now = Instant.now();
        active.forEach(c -> {
            if (c.state().isTerminal()) return;
            store.save(c.toBuilder()
                    .state(CommitmentState.EXPIRED)
                    .resolvedAt(now)
                    .expiresAt(now)
                    .build());
            toFire.add(new CommitmentExpiredEvent(
                    c.id(), c.correlationId(), channelId, c.obligor(), c.requester(), now));
        });
        toFire.forEach(event -> {
            try {
                expiredEvents.fire(event);
            } catch (Exception e) {
                LOG.warnf(e, "CommitmentExpiredEvent observer failed for commitment %s — continuing", event.commitmentId());
            }
        });
        return toFire.size();
    }
}
