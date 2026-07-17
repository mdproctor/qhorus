package io.casehub.qhorus.notification.bridge;

import io.casehub.platform.api.notification.NotificationInput;
import io.casehub.platform.api.notification.NotificationSeverity;
import io.casehub.platform.api.notification.NotificationSource;
import io.casehub.platform.api.notification.NotificationStore;
import io.casehub.qhorus.api.gateway.MessageObserver;
import io.casehub.qhorus.api.gateway.MessageReceivedEvent;
import io.casehub.qhorus.api.message.Commitment;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.api.store.CommitmentStore;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;

import java.util.Optional;
import java.util.UUID;

import static io.casehub.qhorus.notification.bridge.NotificationCategories.*;

@ApplicationScoped
public class NotificationBridgeObserver implements MessageObserver {

    private static final Logger LOG = Logger.getLogger(NotificationBridgeObserver.class);

    private final CommitmentStore commitmentStore;
    private final NotificationStore notificationStore;

    @Inject
    public NotificationBridgeObserver(CommitmentStore commitmentStore,
                                      NotificationStore notificationStore) {
        this.commitmentStore = commitmentStore;
        this.notificationStore = notificationStore;
    }

    @Override
    public void onMessage(MessageReceivedEvent event) {
        if (event.correlationId() == null) {
            return;
        }
        switch (event.messageType()) {
            case COMMAND -> notifyObligorAssigned(event);
            case DONE    -> notifyRequesterResolved(event, OBLIGATION_FULFILLED, "Request completed");
            case FAILURE -> notifyRequesterResolved(event, OBLIGATION_FAILED, "Request failed");
            default -> { /* STATUS, RESPONSE, QUERY, EVENT, HANDOFF, DECLINE — no notification */ }
        }
    }

    @Override
    public Scope scope() {
        return Scope.LOCAL;
    }

    private void notifyObligorAssigned(MessageReceivedEvent event) {
        Optional<Commitment> commitment = commitmentStore.findByCorrelationId(event.correlationId());
        if (commitment.isEmpty()) {
            LOG.debugf("No commitment for correlationId=%s — skipping COMMAND notification", event.correlationId());
            return;
        }
        String obligor = commitment.get().obligor();
        if (obligor == null || obligor.isBlank()) {
            return;
        }
        store(obligor,
              event.tenancyId(),
              "Obligation assigned in #" + event.channelName(),
              truncate(event.content(), 200),
              OBLIGATION_ASSIGNED,
              NotificationSeverity.INFO,
              event.channelId(),
              event.senderId(),
              event.correlationId());
    }

    private void notifyRequesterResolved(MessageReceivedEvent event, String category, String titlePrefix) {
        Optional<Commitment> commitment = commitmentStore.findByCorrelationId(event.correlationId());
        if (commitment.isEmpty()) {
            return;
        }
        String requester = commitment.get().requester();
        if (requester == null || requester.isBlank()) {
            return;
        }
        if (requester.equals(event.senderId())) {
            return;
        }
        NotificationSeverity severity = OBLIGATION_FAILED.equals(category)
                ? NotificationSeverity.WARNING
                : NotificationSeverity.INFO;
        store(requester,
              event.tenancyId(),
              titlePrefix + " in #" + event.channelName(),
              truncate(event.content(), 200),
              category,
              severity,
              event.channelId(),
              event.senderId(),
              event.correlationId());
    }

    private void store(String userId, String tenancyId, String title, String body,
                       String category, NotificationSeverity severity,
                       UUID channelId, String actorId, String correlationId) {
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
                            channelId.toString(),
                            actorId)));
        } catch (Exception e) {
            LOG.warnf("Failed to store notification for user=%s category=%s: %s",
                      userId, category, e.getMessage());
        }
    }

    static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
