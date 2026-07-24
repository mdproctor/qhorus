package io.casehub.qhorus.api.watchdog;

import java.util.List;
import java.util.UUID;

public record BarrierStuckContext(
        UUID channelId,
        String channelName,
        List<String> missingContributors,
        List<String> notDelivered,
        List<String> deliveredNoResponse,
        long elapsedSeconds) implements AlertContext {

    public BarrierStuckContext(UUID channelId, String channelName,
                               List<String> missingContributors, long elapsedSeconds) {
        this(channelId, channelName, missingContributors, List.of(), List.of(), elapsedSeconds);
    }

    @Override
    public WatchdogConditionType conditionType() {return WatchdogConditionType.BARRIER_STUCK;}
}
