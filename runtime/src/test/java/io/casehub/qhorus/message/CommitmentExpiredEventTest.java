package io.casehub.qhorus.message;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.casehub.qhorus.api.message.CommitmentExpiredEvent;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.message.CommitmentService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;

/**
 * Verifies that {@link CommitmentService#expireOverdue()} fires {@link CommitmentExpiredEvent}
 * once per expired commitment.
 *
 * <p>Uses unique correlation IDs per test — no store clearing needed.
 * Refs #281.
 */
@QuarkusTest
class CommitmentExpiredEventTest {

    @Inject CommitmentService commitmentService;
    @Inject EventCapture capture;

    @Test
    void expireOverdue_firesEventPerExpiredCommitment() {
        final String correlationId = "corr-expired-" + UUID.randomUUID();
        final UUID commitmentId = UUID.randomUUID();
        final UUID channelId = UUID.randomUUID();
        final Instant deadline = Instant.now().minusSeconds(1); // already past

        QuarkusTransaction.requiringNew().run(() ->
                commitmentService.open(commitmentId, correlationId, channelId,
                        MessageType.COMMAND, "requester-a", "obligor-b", deadline));

        QuarkusTransaction.requiringNew().run(() ->
                commitmentService.expireOverdue());

        final List<CommitmentExpiredEvent> forCorr = capture.events().stream()
                .filter(e -> correlationId.equals(e.correlationId()))
                .toList();

        assertThat(forCorr)
                .hasSize(1)
                .first()
                .satisfies(e -> {
                    assertThat(e.commitmentId()).isEqualTo(commitmentId);
                    assertThat(e.channelId()).isEqualTo(channelId);
                    assertThat(e.obligor()).isEqualTo("obligor-b");
                    assertThat(e.requester()).isEqualTo("requester-a");
                    assertThat(e.expiresAt()).isEqualTo(deadline);
                });
    }

    @Test
    void expireOverdue_calledTwice_doesNotFireEventTwice() {
        final String correlationId = "corr-expired-idem-" + UUID.randomUUID();
        QuarkusTransaction.requiringNew().run(() ->
                commitmentService.open(UUID.randomUUID(), correlationId, UUID.randomUUID(),
                        MessageType.COMMAND, "req", "obl", Instant.now().minusSeconds(1)));

        QuarkusTransaction.requiringNew().run(() -> commitmentService.expireOverdue());
        QuarkusTransaction.requiringNew().run(() -> commitmentService.expireOverdue());

        assertThat(capture.events().stream()
                .filter(e -> correlationId.equals(e.correlationId())).count())
                .isEqualTo(1); // idempotent — already EXPIRED on second call, transition guard skips it
    }

    @Test
    void expireOverdue_futureDeadline_doesNotFire() {
        final String correlationId = "corr-future-" + UUID.randomUUID();
        QuarkusTransaction.requiringNew().run(() ->
                commitmentService.open(UUID.randomUUID(), correlationId, UUID.randomUUID(),
                        MessageType.COMMAND, "req", "obl", Instant.now().plusSeconds(3600)));

        QuarkusTransaction.requiringNew().run(() -> commitmentService.expireOverdue());

        assertThat(capture.events().stream()
                .filter(e -> correlationId.equals(e.correlationId())).count())
                .isZero();
    }

    @ApplicationScoped
    public static class EventCapture {
        private final List<CommitmentExpiredEvent> captured = new CopyOnWriteArrayList<>();

        void onEvent(@Observes CommitmentExpiredEvent event) { captured.add(event); }
        List<CommitmentExpiredEvent> events() { return List.copyOf(captured); }
    }
}
