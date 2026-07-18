package io.casehub.qhorus.api.watchdog;

import java.util.List;
import java.util.UUID;

public record ConversationStallContext(
        UUID channelId, String channelName,
        int stalledCount, List<String> correlationIds, long stalledSeconds
) implements AlertContext {
    @Override
    public WatchdogConditionType conditionType() { return WatchdogConditionType.CONVERSATION_STALL; }
}