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
 *   <li>Connector binding completeness (all four fields present or all null)</li>
 *   <li>Type names in {@code allowedTypes} and {@code deniedTypes} are valid {@link MessageType} values</li>
 *   <li>{@code allowedTypes} and {@code deniedTypes} do not intersect (denial wins by construction)</li>
 * </ul>
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
        String allowedTypes,
        String deniedTypes,
        // Connector binding — all four non-null together, or all null
        String inboundConnectorId,
        String externalKey,
        String outboundConnectorId,
        String outboundDestination
) {
    public ChannelCreateRequest {
        boolean anySet = inboundConnectorId != null || externalKey != null
                || outboundConnectorId != null || outboundDestination != null;
        boolean allSet = inboundConnectorId != null && externalKey != null
                && outboundConnectorId != null && outboundDestination != null;
        if (anySet && !allSet) {
            throw new IllegalArgumentException(
                    "Connector binding requires all four fields: inboundConnectorId, " +
                    "externalKey, outboundConnectorId, outboundDestination");
        }

        // Validate type names are valid MessageType values and allowedTypes ∩ deniedTypes = ∅
        Set<MessageType> allowed = MessageType.parseTypes(allowedTypes);   // throws on invalid name
        Set<MessageType> denied  = MessageType.parseTypes(deniedTypes);    // throws on invalid name
        if (!allowed.isEmpty() && !denied.isEmpty()) {
            Set<MessageType> overlap = new HashSet<>(allowed);
            overlap.retainAll(denied);
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
    public static ChannelCreateRequest simple(String name, ChannelSemantic semantic) {
        return new ChannelCreateRequest(name, null, semantic, null,
                null, null, null, null,
                null,   // allowedTypes
                null,   // deniedTypes
                null, null, null, null);
    }
}
