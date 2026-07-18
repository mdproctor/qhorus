package io.casehub.qhorus.api.watchdog;

import java.util.List;
import java.util.UUID;

public record EchoChamberContext(
        UUID channelId, String channelName,
        List<String> participants, double maxSimilarity
) implements AlertContext {
    @Override
    public WatchdogConditionType conditionType() { return WatchdogConditionType.ECHO_CHAMBER; }
}