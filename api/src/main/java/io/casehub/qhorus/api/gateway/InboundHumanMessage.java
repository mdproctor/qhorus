package io.casehub.qhorus.api.gateway;

import java.time.Instant;
import java.util.Map;

public record InboundHumanMessage(
        String externalSenderId,
        String content,
        Instant receivedAt,
        Map<String, String> metadata) {}
