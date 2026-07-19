package io.casehub.qhorus.runtime.channel;

import io.casehub.qhorus.api.channel.Channel;
import io.casehub.qhorus.api.channel.ChannelSummary;
import io.casehub.qhorus.api.channel.ChannelSummaryUpdatedEvent;
import io.casehub.qhorus.api.spi.SummaryUpdateContext;
import io.casehub.qhorus.api.spi.SummaryUpdateHook;
import io.casehub.qhorus.api.store.ChannelSummaryStore;
import io.casehub.qhorus.api.store.MessageStore;
import io.casehub.qhorus.api.store.query.MessageQuery;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class ChannelSummaryService {

    @Inject
    ChannelSummaryStore summaryStore;

    @Inject
    ChannelService channelService;

    @Inject
    MessageStore messageStore;

    @Inject
    SummaryUpdateHook hook;

    @Inject
    Event<ChannelSummaryUpdatedEvent> summaryEvents;

    public Optional<ChannelSummary> getSummary(UUID channelId) {
        return summaryStore.findByChannelId(channelId);
    }

    public ChannelSummary setSummary(UUID channelId, String content, String updatedBy) {
        Channel ch = channelService.findById(channelId)
                .orElseThrow(() -> new IllegalArgumentException("Channel not found: " + channelId));

        Long maxMessageId = currentMaxMessageId(channelId);

        ChannelSummary existing = summaryStore.findByChannelId(channelId).orElse(null);
        ChannelSummary.Builder b = existing != null
                ? existing.toBuilder()
                : ChannelSummary.builder(channelId).tenancyId(ch.tenancyId());

        ChannelSummary saved = summaryStore.save(b
                .content(content)
                .updatedAt(Instant.now())
                .updatedBy(updatedBy)
                .lastUpdatedMessageId(maxMessageId)
                .build());

        summaryEvents.fireAsync(new ChannelSummaryUpdatedEvent(channelId, ch.name(), updatedBy));
        return saved;
    }

    public ChannelSummary configureSummary(UUID channelId, Integer updateAfterMessages, Integer updateAfterSeconds) {
        if (updateAfterMessages != null && updateAfterMessages < 1) {
            throw new IllegalArgumentException("updateAfterMessages must be >= 1 or null");
        }
        if (updateAfterSeconds != null && updateAfterSeconds < 1) {
            throw new IllegalArgumentException("updateAfterSeconds must be >= 1 or null");
        }

        Channel ch = channelService.findById(channelId)
                .orElseThrow(() -> new IllegalArgumentException("Channel not found: " + channelId));

        ChannelSummary existing = summaryStore.findByChannelId(channelId).orElse(null);
        ChannelSummary.Builder b = existing != null
                ? existing.toBuilder()
                : ChannelSummary.builder(channelId).tenancyId(ch.tenancyId());

        return summaryStore.save(b
                .updateAfterMessages(updateAfterMessages)
                .updateAfterSeconds(updateAfterSeconds)
                .build());
    }

    public Optional<ChannelSummary> triggerUpdate(UUID channelId) {
        ChannelSummary existing = summaryStore.findByChannelId(channelId).orElse(null);
        if (existing == null) {
            return Optional.empty();
        }

        Channel ch = channelService.findById(channelId)
                .orElseThrow(() -> new IllegalArgumentException("Channel not found: " + channelId));

        long messagesSince = countMessagesSince(channelId, existing.lastUpdatedMessageId());

        String updated = hook.update(new SummaryUpdateContext(
                channelId, ch.name(), ch.tenancyId(),
                existing.content(), existing.lastUpdatedMessageId(), messagesSince));

        Long maxMessageId = currentMaxMessageId(channelId);

        ChannelSummary saved = summaryStore.save(existing.toBuilder()
                .content(updated)
                .updatedAt(Instant.now())
                .updatedBy("system:summary-scheduler")
                .lastUpdatedMessageId(maxMessageId)
                .build());

        summaryEvents.fireAsync(new ChannelSummaryUpdatedEvent(channelId, ch.name(), "system:summary-scheduler"));
        return Optional.of(saved);
    }

    public void deleteSummary(UUID channelId) {
        summaryStore.deleteByChannelId(channelId);
    }

    long countMessagesSince(UUID channelId, Long afterId) {
        if (afterId == null) {
            return messageStore.count(MessageQuery.builder().channelId(channelId).build());
        }
        return messageStore.count(MessageQuery.builder().channelId(channelId).afterId(afterId).build());
    }

    Long currentMaxMessageId(UUID channelId) {
        var msgs = messageStore.scan(MessageQuery.builder()
                .channelId(channelId).limit(1).descending(true).build());
        return msgs.isEmpty() ? null : msgs.get(0).id();
    }
}
