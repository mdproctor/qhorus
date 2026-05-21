package io.casehub.qhorus.api.message;

import java.util.List;

public record MessageResult(
        Long messageId,
        String channelName,
        String sender,
        String messageType,
        String correlationId,
        Long inReplyTo,
        int parentReplyCount,
        List<String> artefactRefs,
        /** Addressing target: null (broadcast), instance:<id>, capability:<tag>, or role:<name>. */
        String target) {
}
