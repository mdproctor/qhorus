package io.casehub.qhorus.notification.bridge;

import io.casehub.platform.api.notification.NotificationInput;
import io.casehub.platform.api.notification.NotificationSeverity;
import io.casehub.platform.api.notification.NotificationSource;
import io.casehub.platform.api.notification.NotificationStore;
import io.casehub.qhorus.api.message.Commitment;
import io.casehub.qhorus.api.message.CommitmentDeclinedEvent;
import io.casehub.qhorus.api.message.CommitmentExpiredEvent;
import io.casehub.qhorus.api.store.CommitmentStore;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;

import java.util.Optional;

import static io.casehub.qhorus.notification.bridge.NotificationCategories.*;

@ApplicationScoped
public class CommitmentEventNotifier {

    private static final Logger LOG = Logger.getLogger(CommitmentEventNotifier.class);

    private final CommitmentStore commitmentStore;
    private final NotificationStore notificationStore;

    @Inject
    public CommitmentEventNotifier(CommitmentStore commitmentStore,
                                    NotificationStore notificationStore) {
        this.commitmentStore = commitmentStore;
        this.notificationStore = notificationStore;
    }

    void onDeclined(@ObservesAsync CommitmentDeclinedEvent event) {
        String requester = event.requester();
        if (requester == null || requester.isBlank()) {
            return;
        }
        Optional<Commitment> commitment = commitmentStore.findById(event.commitmentId());
        String tenancyId = commitment.map(Commitment::tenancyId).orElse("DEFAULT");

        store(requester,
              tenancyId,
              "Request declined by " + event.obligor(),
              null,
              OBLIGATION_DECLINED,
              NotificationSeverity.WARNING,
              event.channelId().toString(),
              event.obligor(),
              event.correlationId());
    }

    void onExpired(@ObservesAsync CommitmentExpiredEvent event) {
        String requester = event.requester();
        if (requester == null || requester.isBlank()) {
            return;
        }
        Optional<Commitment> commitment = commitmentStore.findById(event.commitmentId());
        String tenancyId = commitment.map(Commitment::tenancyId).orElse("DEFAULT");

        String obligorLabel = event.obligor() != null ? event.obligor() : "unassigned";
        store(requester,
              tenancyId,
              "Request expired — " + obligorLabel + " did not respond",
              null,
              OBLIGATION_EXPIRED,
              NotificationSeverity.URGENT,
              event.channelId().toString(),
              obligorLabel,
              event.correlationId());
    }

    private void store(String userId, String tenancyId, String title, String body,
                       String category, NotificationSeverity severity,
                       String channelId, String actorId, String correlationId) {
        try {
            notificationStore.store(new NotificationInput(
                    userId,
                    tenancyId,
                    title,
                    body,
                    category,
                    severity,
                    null,
                    new NotificationSource(
                            correlationId,
                            ENTITY_TYPE,
                            channelId,
                            actorId)));
        } catch (Exception e) {
            LOG.warnf("Failed to store notification for user=%s category=%s: %s",
                      userId, category, e.getMessage());
        }
    }
}
