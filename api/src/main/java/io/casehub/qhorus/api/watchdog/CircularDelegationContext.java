package io.casehub.qhorus.api.watchdog;

import java.util.List;
import java.util.UUID;

public record CircularDelegationContext(
        UUID channelId, String channelName,
        String correlationId, List<String> cycle, int chainDepth
) implements AlertContext {
    @Override
    public WatchdogConditionType conditionType() {return WatchdogConditionType.CIRCULAR_DELEGATION;}
}