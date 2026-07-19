package io.casehub.qhorus.api.spi;

import java.util.UUID;

public record SummaryUpdateContext(
        UUID channelId,
        String channelName,
        String tenancyId,
        String currentSummary,
        Long lastUpdatedMessageId,
        long messagesSinceLastUpdate) {}
