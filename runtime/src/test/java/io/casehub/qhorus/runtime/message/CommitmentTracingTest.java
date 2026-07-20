package io.casehub.qhorus.runtime.message;

import io.casehub.qhorus.api.message.Commitment;
import io.casehub.qhorus.api.message.CommitmentDeclinedEvent;
import io.casehub.qhorus.api.message.CommitmentExpiredEvent;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.api.store.CommitmentStore;
import io.casehub.qhorus.runtime.config.QhorusTracingConfig;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.NotificationOptions;
import jakarta.enterprise.util.TypeLiteral;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

import static org.assertj.core.api.Assertions.assertThat;

class CommitmentTracingTest {

    /**
     * Minimal stub store for tracing tests — only needs to support findByCorrelationId and save.
     * All other operations throw UnsupportedOperationException.
     */
    static class StubCommitmentStore implements CommitmentStore {
        private final java.util.Map<String, Commitment> byCorrelationId = new java.util.concurrent.ConcurrentHashMap<>();
        private final java.util.Map<UUID, Commitment> byId = new java.util.concurrent.ConcurrentHashMap<>();

        @Override
        public Commitment save(Commitment commitment) {
            byCorrelationId.put(commitment.correlationId(), commitment);
            byId.put(commitment.id(), commitment);
            return commitment;
        }

        @Override
        public java.util.Optional<Commitment> findByCorrelationId(String correlationId) {
            return java.util.Optional.ofNullable(byCorrelationId.get(correlationId));
        }

        @Override
        public java.util.List<Commitment> findAllByCorrelationId(String correlationId) {
            return byId.values().stream()
                       .filter(c -> correlationId.equals(c.correlationId()))
                       .sorted(java.util.Comparator.comparing(c -> c.createdAt() != null ? c.createdAt() : Instant.MIN))
                       .toList();
        }


        @Override
        public java.util.List<Commitment> findByIds(java.util.Collection<UUID> ids) {
            return ids.stream().map(byId::get).filter(java.util.Objects::nonNull).toList();
        }


        @Override
        public java.util.Optional<Commitment> findById(UUID id) {
            return java.util.Optional.ofNullable(byId.get(id));
        }

        @Override
        public List<Commitment> findExpiredBefore(Instant cutoff) {
            return byId.values().stream()
                    .filter(c -> c.expiresAt() != null && c.expiresAt().isBefore(cutoff))
                    .filter(c -> c.state().isActive())
                    .toList();
        }

        @Override
        public List<Commitment> findOpenByObligor(String obligor, UUID channelId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<Commitment> findOpenByRequester(String requester, UUID channelId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<Commitment> findByState(io.casehub.qhorus.api.message.CommitmentState state, UUID channelId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<Commitment> findByChannel(UUID channelId) {
            throw new UnsupportedOperationException();
        }


        @Override
        public List<Commitment> findAllOpen() {
            throw new UnsupportedOperationException();
        }

        @Override
        public long deleteAll(UUID channelId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long deleteExpiredBefore(Instant cutoff) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void deleteById(UUID id) {
            throw new UnsupportedOperationException();
        }

        void clear() {
            byCorrelationId.clear();
            byId.clear();
        }

        @Override
        public List<Commitment> findOpenByChannelId(UUID channelId) {return List.of();}
    }

    private InMemorySpanExporter exporter;
    private CommitmentService service;
    private StubCommitmentStore store;

    @BeforeEach
    void setUp() {
        exporter = InMemorySpanExporter.create();
        SdkTracerProvider provider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();

        store = new StubCommitmentStore();
        service = new CommitmentService();
        service.store = store;
        service.tracerInstance = new jakarta.enterprise.inject.Instance<io.opentelemetry.api.trace.Tracer>() {
            @Override
            public io.opentelemetry.api.trace.Tracer get() {
                return provider.get("qhorus-test");
            }

            @Override
            public boolean isResolvable() {
                return true;
            }

            @Override
            public boolean isAmbiguous() {
                return false;
            }

            @Override
            public boolean isUnsatisfied() {
                return false;
            }

            @Override
            public jakarta.enterprise.inject.Instance<io.opentelemetry.api.trace.Tracer> select(Annotation... qualifiers) {
                return this;
            }

            @Override
            public <U extends io.opentelemetry.api.trace.Tracer> jakarta.enterprise.inject.Instance<U> select(Class<U> subtype, Annotation... qualifiers) {
                throw new UnsupportedOperationException();
            }

            @Override
            public <U extends io.opentelemetry.api.trace.Tracer> jakarta.enterprise.inject.Instance<U> select(TypeLiteral<U> subtype, Annotation... qualifiers) {
                throw new UnsupportedOperationException();
            }

            @Override
            public jakarta.enterprise.inject.Instance.Handle<io.opentelemetry.api.trace.Tracer> getHandle() {
                throw new UnsupportedOperationException();
            }

            @Override
            public Iterable<? extends jakarta.enterprise.inject.Instance.Handle<io.opentelemetry.api.trace.Tracer>> handles() {
                throw new UnsupportedOperationException();
            }

            @Override
            public java.util.stream.Stream<io.opentelemetry.api.trace.Tracer> stream() {
                throw new UnsupportedOperationException();
            }

            @Override
            public void destroy(io.opentelemetry.api.trace.Tracer instance) {
                throw new UnsupportedOperationException();
            }

            @Override
            public java.util.Iterator<io.opentelemetry.api.trace.Tracer> iterator() {
                throw new UnsupportedOperationException();
            }
        };
        service.tracingConfig = new QhorusTracingConfig() {
            @Override public boolean enabled() { return true; }
            @Override public boolean dispatch() { return true; }
            @Override public boolean commitments() { return true; }
            @Override public boolean fanOut() { return true; }
            @Override public boolean ledgerWrite() { return true; }
            @Override public boolean delivery() { return true; }
        };

        // No-op event producers for decline/expire tests
        service.declinedEvents = new Event<CommitmentDeclinedEvent>() {
            @Override
            public void fire(CommitmentDeclinedEvent event) {}

            @Override
            public <U extends CommitmentDeclinedEvent> CompletionStage<U> fireAsync(U event) {
                throw new UnsupportedOperationException();
            }

            @Override
            public <U extends CommitmentDeclinedEvent> CompletionStage<U> fireAsync(U event, NotificationOptions options) {
                throw new UnsupportedOperationException();
            }

            @Override
            public <U extends CommitmentDeclinedEvent> Event<U> select(Class<U> subtype, Annotation... qualifiers) {
                throw new UnsupportedOperationException();
            }

            @Override
            public <U extends CommitmentDeclinedEvent> Event<U> select(TypeLiteral<U> subtype, Annotation... qualifiers) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Event<CommitmentDeclinedEvent> select(Annotation... qualifiers) {
                throw new UnsupportedOperationException();
            }
        };

        service.expiredEvents = new Event<CommitmentExpiredEvent>() {
            @Override
            public void fire(CommitmentExpiredEvent event) {}

            @Override
            public <U extends CommitmentExpiredEvent> CompletionStage<U> fireAsync(U event) {
                throw new UnsupportedOperationException();
            }

            @Override
            public <U extends CommitmentExpiredEvent> CompletionStage<U> fireAsync(U event, NotificationOptions options) {
                throw new UnsupportedOperationException();
            }

            @Override
            public <U extends CommitmentExpiredEvent> Event<U> select(Class<U> subtype, Annotation... qualifiers) {
                throw new UnsupportedOperationException();
            }

            @Override
            public <U extends CommitmentExpiredEvent> Event<U> select(TypeLiteral<U> subtype, Annotation... qualifiers) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Event<CommitmentExpiredEvent> select(Annotation... qualifiers) {
                throw new UnsupportedOperationException();
            }
        };
    }

    @Test
    void open_creates_commitment_span() {
        UUID channelId = UUID.randomUUID();
        UUID commitmentId = UUID.randomUUID();

        service.open(commitmentId, "corr-1", channelId,
                MessageType.COMMAND, "requester", "obligor", null);

        List<SpanData> spans = exporter.getFinishedSpanItems().stream()
                .filter(s -> s.getName().equals("qhorus.commitment.open"))
                .toList();

        assertThat(spans).hasSize(1);
        SpanData span = spans.get(0);
        assertThat(span.getAttributes().get(
                AttributeKey.stringKey("qhorus.commitment.id")))
                .isEqualTo(commitmentId.toString());
        assertThat(span.getAttributes().get(
                AttributeKey.stringKey("qhorus.commitment.to_state")))
                .isEqualTo("OPEN");
        assertThat(span.getAttributes().get(
                AttributeKey.stringKey("qhorus.commitment.correlation_id")))
                .isEqualTo("corr-1");
        assertThat(span.getAttributes().get(
                AttributeKey.stringKey("qhorus.commitment.obligor")))
                .isEqualTo("obligor");
        assertThat(span.getAttributes().get(
                AttributeKey.stringKey("qhorus.channel.id")))
                .isEqualTo(channelId.toString());
    }

    @Test
    void fulfill_creates_span_with_state_transition() {
        UUID channelId = UUID.randomUUID();
        UUID commitmentId = UUID.randomUUID();
        service.open(commitmentId, "corr-2", channelId,
                MessageType.COMMAND, "req", "obl", null);
        exporter.reset();

        service.fulfill("corr-2");

        List<SpanData> spans = exporter.getFinishedSpanItems().stream()
                .filter(s -> s.getName().equals("qhorus.commitment.fulfill"))
                .toList();

        assertThat(spans).hasSize(1);
        assertThat(spans.get(0).getAttributes().get(
                AttributeKey.stringKey("qhorus.commitment.from_state")))
                .isEqualTo("OPEN");
        assertThat(spans.get(0).getAttributes().get(
                AttributeKey.stringKey("qhorus.commitment.to_state")))
                .isEqualTo("FULFILLED");
    }

    @Test
    void no_span_when_commitments_tracing_disabled() {
        service.tracingConfig = new QhorusTracingConfig() {
            @Override public boolean enabled() { return true; }
            @Override public boolean dispatch() { return true; }
            @Override public boolean commitments() { return false; }
            @Override public boolean fanOut() { return true; }
            @Override public boolean ledgerWrite() { return true; }
            @Override public boolean delivery() { return true; }
        };

        service.open(UUID.randomUUID(), "corr-3", UUID.randomUUID(),
                MessageType.COMMAND, "req", "obl", null);

        assertThat(exporter.getFinishedSpanItems()).isEmpty();
    }

    @Test
    void expire_overdue_creates_root_span() {
        // Create some expired commitments
        service.open(UUID.randomUUID(), "exp-1", UUID.randomUUID(),
                MessageType.COMMAND, "req", "obl", Instant.now().minusSeconds(10));
        service.open(UUID.randomUUID(), "exp-2", UUID.randomUUID(),
                MessageType.COMMAND, "req", "obl", Instant.now().minusSeconds(5));
        exporter.reset();

        service.expireOverdue();

        List<SpanData> spans = exporter.getFinishedSpanItems().stream()
                .filter(s -> s.getName().equals("qhorus.commitment.expire_overdue"))
                .toList();

        assertThat(spans).hasSize(1);
        SpanData span = spans.get(0);
        // Verify it's a root span (no parent)
        assertThat(span.getParentSpanContext().isValid()).isFalse();
        // Verify expired count
        assertThat(span.getAttributes().get(
                AttributeKey.longKey("qhorus.commitment.expired_count")))
                .isEqualTo(2L);
    }
}
