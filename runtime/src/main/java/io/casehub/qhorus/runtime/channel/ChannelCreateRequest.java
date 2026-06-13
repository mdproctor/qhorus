package io.casehub.qhorus.runtime.channel;

import java.util.HashSet;
import java.util.Set;

import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.casehub.qhorus.api.message.MessageType;

/**
 * Request record for creating a Qhorus channel with an optional connector binding.
 * If {@code inboundConnectorId} is non-null, all four binding fields must be non-null.
 *
 * <p>The compact constructor validates:
 * <ul>
 *   <li>Channel name is a well-formed slug path</li>
 *   <li>Connector binding completeness (all four fields present or all null)</li>
 *   <li>{@code allowedTypes} and {@code deniedTypes} do not intersect (denial wins by construction)</li>
 * </ul>
 *
 * <p>{@code null} for {@code allowedTypes} or {@code deniedTypes} means "open" (no restriction).
 * The record preserves null as-is — null and an empty set are distinct at the API level but
 * both serialize to {@code null} at the persistence boundary via {@link MessageType#serializeTypes}.
 */
public record ChannelCreateRequest(
        String name,
        String description,
        ChannelSemantic semantic,
        String barrierContributors,
        String allowedWriters,
        String adminInstances,
        Integer rateLimitPerChannel,
        Integer rateLimitPerInstance,
        Set<MessageType> allowedTypes,
        Set<MessageType> deniedTypes,
        // Connector binding — all four non-null together, or all null
        String inboundConnectorId,
        String externalKey,
        String outboundConnectorId,
        String outboundDestination
) {
    public ChannelCreateRequest {
        ChannelSlugValidator.validateSlugPath(name);
        boolean anySet = inboundConnectorId != null || externalKey != null
                || outboundConnectorId != null || outboundDestination != null;
        boolean allSet = inboundConnectorId != null && externalKey != null
                && outboundConnectorId != null && outboundDestination != null;
        if (anySet && !allSet) {
            throw new IllegalArgumentException(
                    "Connector binding requires all four fields: inboundConnectorId, " +
                    "externalKey, outboundConnectorId, outboundDestination");
        }

        // Defensive copy — record fields must be immutable; caller mutation after construction
        // must not alter the validated state. Set.copyOf(null) throws NPE, hence the null guard.
        // Null is preserved (not normalized to Set.of()) — null means "open" and is a meaningful
        // contract distinct from "empty allowed set (nothing permitted)".
        allowedTypes = allowedTypes != null ? Set.copyOf(allowedTypes) : null;
        deniedTypes  = deniedTypes  != null ? Set.copyOf(deniedTypes)  : null;

        // Validate overlap — both fields may be null (null means "open", not "block all")
        if (allowedTypes != null && !allowedTypes.isEmpty()
                && deniedTypes != null && !deniedTypes.isEmpty()) {
            final Set<MessageType> overlap = new HashSet<>(allowedTypes);
            overlap.retainAll(deniedTypes);
            if (!overlap.isEmpty()) {
                throw new IllegalArgumentException(
                        "allowedTypes and deniedTypes must not intersect. Overlap: " + overlap);
            }
        }
    }

    public boolean hasConnectorBinding() {
        return inboundConnectorId != null;
    }

    /** Convenience factory — no connector binding, no type restrictions. */
    public static ChannelCreateRequest simple(final String name, final ChannelSemantic semantic) {
        return new ChannelCreateRequest(name, null, semantic, null,
                null, null, null, null,
                null,   // allowedTypes — null means "open"
                null,   // deniedTypes — null means "open"
                null, null, null, null);
    }
}
