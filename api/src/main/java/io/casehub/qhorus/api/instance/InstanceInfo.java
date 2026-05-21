package io.casehub.qhorus.api.instance;

import java.util.List;

public record InstanceInfo(
        String instanceId,
        String description,
        String status,
        List<String> capabilities,
        String lastSeen,
        boolean readOnly) {
}
