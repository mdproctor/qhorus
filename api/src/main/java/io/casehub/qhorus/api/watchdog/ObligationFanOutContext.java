package io.casehub.qhorus.api.watchdog;

import java.util.List;
import java.util.UUID;

public record ObligationFanOutContext(
        UUID channelId, String channelName,
        int staleCount, List<String> correlationIds
) implements AlertContext {
    @Override
    public WatchdogConditionType conditionType() { return WatchdogConditionType.OBLIGATION_FAN_OUT; }
}