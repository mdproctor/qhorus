package io.casehub.qhorus.runtime.capacity;

import io.casehub.platform.api.capacity.ActorCapacity;
import io.casehub.platform.api.capacity.ActorCapacityView;
import io.casehub.platform.api.capacity.CapacityPressureEvent;
import io.casehub.platform.api.capacity.RedistributionContext;
import io.casehub.platform.api.capacity.RedistributionDecision;
import io.casehub.platform.api.capacity.RedistributionPolicy;
import io.casehub.qhorus.api.message.Commitment;
import io.casehub.qhorus.api.store.CrossTenantCommitmentStore;
import io.casehub.qhorus.runtime.ledger.MessageLedgerEntryRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@ApplicationScoped
public class QhorusRedistributionExecutor {

    private static final Logger LOG = Logger.getLogger(QhorusRedistributionExecutor.class);

    @Inject RedistributionDelegate delegate;
    @Inject RedistributionPolicy policy;
    @Inject CrossTenantCommitmentStore commitmentStore;
    @Inject MessageLedgerEntryRepository messageRepo;

    QhorusRedistributionExecutor() {}

    QhorusRedistributionExecutor(RedistributionDelegate delegate, RedistributionPolicy policy,
                                  CrossTenantCommitmentStore commitmentStore,
                                  MessageLedgerEntryRepository messageRepo) {
        this.delegate = delegate;
        this.policy = policy;
        this.commitmentStore = commitmentStore;
        this.messageRepo = messageRepo;
    }

    void onCapacityPressure(@ObservesAsync CapacityPressureEvent event) {
        String actorId = event.actorId();

        List<Commitment> obligations = commitmentStore.findOpenByObligor(actorId);

        Duration timeSinceLastActivity = messageRepo.findLatestEntryByActor(actorId)
                .map(e -> Duration.between(e.occurredAt, Instant.now()))
                .orElse(Duration.ofDays(365));

        RedistributionContext context = new RedistributionContext(
                actorId, event.capacity(), event.triggerSignalType(),
                obligations.size(), timeSinceLastActivity);

        RedistributionDecision decision = policy.evaluate(context);

        switch (decision) {
            case RedistributionDecision.Compress c -> {
                LOG.debugf("Compress for %s: %s", actorId, c.reason());
                delegate.compress(actorId, obligations);
            }
            case RedistributionDecision.Redistribute r -> {
                var lastDelegated = commitmentStore.findLatestDelegatedByObligor(actorId);
                if (lastDelegated.isPresent()
                        && !r.gracePeriod().isZero()
                        && lastDelegated.get().resolvedAt() != null
                        && Duration.between(lastDelegated.get().resolvedAt(), Instant.now())
                                .compareTo(r.gracePeriod()) < 0) {
                    LOG.debugf("Grace period active for %s — skipping redistribution", actorId);
                    return;
                }
                LOG.infof("Redistributing for %s: %s", actorId, r.reason());
                RedistributionResult result = delegate.redistribute(
                        actorId, obligations, r, event.capacity().aggregatePressure());
                if (result.successCount() == 0 && result.totalCount() > 0) {
                    delegate.escalate(actorId, "redistribution requested but no targets available");
                }
            }
            case RedistributionDecision.Hold h ->
                    LOG.debugf("Hold for %s: %s", actorId, h.reason());
            case RedistributionDecision.Escalate e -> {
                LOG.warnf("Escalation for %s: %s", actorId, e.reason());
                delegate.escalate(actorId, e.reason());
            }
        }
    }
}
