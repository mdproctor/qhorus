package io.casehub.qhorus.api.watchdog;

import java.util.UUID;

public record LoopDetectedContext(
        UUID channelId, String channelName,
        String sender, int messageCount, double maxSimilarity
) implements AlertContext {
    @Override
    public WatchdogConditionType conditionType() { return WatchdogConditionType.LOOP_DETECTED; }
}