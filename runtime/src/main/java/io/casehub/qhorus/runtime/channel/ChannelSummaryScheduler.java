package io.casehub.qhorus.runtime.channel;

import io.casehub.qhorus.api.channel.Channel;
import io.casehub.qhorus.api.channel.ChannelSummary;
import io.casehub.qhorus.api.channel.ChannelSummaryUpdatedEvent;
import io.casehub.qhorus.api.spi.SummaryUpdateContext;
import io.casehub.qhorus.api.spi.SummaryUpdateHook;
import io.casehub.qhorus.api.store.ChannelSummaryStore;
import io.casehub.qhorus.api.store.CrossTenantChannelSummaryStore;
import io.casehub.qhorus.api.store.CrossTenantChannelStore;
import io.casehub.qhorus.api.store.CrossTenantMessageStore;
import io.casehub.qhorus.api.store.query.MessageQuery;
import io.casehub.qhorus.runtime.config.QhorusConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import io.quarkus.scheduler.Scheduled;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.List;

@ApplicationScoped
public class ChannelSummaryScheduler {

    private static final Logger LOG = Logger.getLogger(ChannelSummaryScheduler.class);

    @Inject
    QhorusConfig config;

    @Inject
    CrossTenantChannelSummaryStore crossTenantSummaryStore;

    @Inject
    ChannelSummaryStore summaryStore;

    @Inject
    CrossTenantChannelStore crossTenantChannelStore;

    @Inject
    CrossTenantMessageStore crossTenantMessageStore;

    @Inject
    SummaryUpdateHook hook;

    @Inject
    Event<ChannelSummaryUpdatedEvent> summaryEvents;

    @Scheduled(every = "${casehub.qhorus.summary.check-interval-seconds:60}s",
               identity = "summary-update-check")
    public void sweep() {
        if (!config.summary().enabled()) {
            return;
        }

        List<ChannelSummary> candidates = crossTenantSummaryStore.findWithAutoUpdateConfigured();
        Instant now = Instant.now();

        for (ChannelSummary s : candidates) {
            try {
                if (shouldUpdate(s, now)) {
                    updateSummary(s);
                }
            } catch (Exception e) {
                LOG.warnf("Summary update failed for channel %s: %s", s.channelId(), e.getMessage());
            }
        }
    }

    boolean shouldUpdate(ChannelSummary s, Instant now) {
        if (s.updateAfterMessages() != null) {
            long newMessages = countMessagesSince(s.channelId(), s.lastUpdatedMessageId());
            if (newMessages >= s.updateAfterMessages()) {
                return true;
            }
        }
        if (s.updateAfterSeconds() != null) {
            if (s.updatedAt() == null) {
                return true;
            }
            long elapsed = now.getEpochSecond() - s.updatedAt().getEpochSecond();
            if (elapsed >= s.updateAfterSeconds()) {
                return true;
            }
        }
        return false;
    }

    void updateSummary(ChannelSummary s) {
        Channel ch = crossTenantChannelStore.listAll().stream()
                .filter(c -> c.id().equals(s.channelId()))
                .findFirst()
                .orElse(null);
        if (ch == null) {
            LOG.warnf("Channel not found for summary update: %s", s.channelId());
            return;
        }

        long messagesSince = countMessagesSince(s.channelId(), s.lastUpdatedMessageId());

        String updated = hook.update(new SummaryUpdateContext(
                s.channelId(), ch.name(), ch.tenancyId(),
                s.content(), s.lastUpdatedMessageId(), messagesSince));

        Long maxMessageId = currentMaxMessageId(s.channelId());

        summaryStore.save(s.toBuilder()
                .content(updated)
                .updatedAt(Instant.now())
                .updatedBy("system:summary-scheduler")
                .lastUpdatedMessageId(maxMessageId)
                .build());

        summaryEvents.fireAsync(new ChannelSummaryUpdatedEvent(s.channelId(), ch.name(), "system:summary-scheduler"));
    }

    private long countMessagesSince(java.util.UUID channelId, Long afterId) {
        if (afterId == null) {
            return crossTenantMessageStore.countByChannel(channelId);
        }
        return crossTenantMessageStore.count(
                MessageQuery.builder().channelId(channelId).afterId(afterId).build());
    }

    private Long currentMaxMessageId(java.util.UUID channelId) {
        var msgs = crossTenantMessageStore.scan(
                MessageQuery.builder().channelId(channelId).limit(1).descending(true).build());
        return msgs.isEmpty() ? null : msgs.get(0).id();
    }
}
