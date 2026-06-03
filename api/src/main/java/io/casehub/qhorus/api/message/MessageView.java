package io.casehub.qhorus.api.message;

import java.time.Instant;
import java.util.UUID;

import io.casehub.platform.api.identity.ActorType;

/**
 * Read-side DTO for a channel message — the fold function's input.
 *
 * <p>This is the canonical read-side representation of a persisted message.
 * {@code ProjectionService} maps {@code Message} entities to {@code MessageView}
 * before passing them to {@link io.casehub.qhorus.api.spi.ChannelProjection#apply}.
 *
 * <p><strong>Field naming:</strong> {@code type} (not {@code messageType}) — consistent
 * with {@code DispatchResult.type}. Code reading entity fields directly should always
 * go through {@code QhorusEntityMapper.toMessageView()}, not access {@code Message.messageType}
 * and assign it to {@code MessageView.type} inline.
 *
 * <p><strong>Excluded fields:</strong> {@code commitmentId} (internal infrastructure UUID)
 * and {@code acknowledgedAt} (null in v1 — will be added when the ACK mechanism ships).
 *
 * <p><strong>{@code replyCount} staleness in incremental projection:</strong>
 * {@code replyCount} is updated in-place on the entity row when replies arrive.
 * An incremental projection folding via {@code afterId} does not re-fold earlier
 * messages, so it will see stale reply counts for messages before the cursor.
 * Projections that rely on {@code replyCount} for thread depth or participation
 * metrics must always perform a full scan — not an incremental one.
 */
public record MessageView(
        Long id,
        UUID channelId,
        String sender,
        MessageType type,
        String content,
        String correlationId,
        Long inReplyTo,
        String target,
        String artefactRefs,
        ActorType actorType,
        Instant createdAt,
        Instant deadline,
        int replyCount) {}
