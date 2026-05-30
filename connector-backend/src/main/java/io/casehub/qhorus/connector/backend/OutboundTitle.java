package io.casehub.qhorus.connector.backend;

import io.casehub.qhorus.api.gateway.ChannelRef;

/**
 * Per-connector outbound title strategy.
 *
 * <p>TODO(v2, qhorus#216): email threading — proper subjects should carry the original
 * In-Reply-To Message-ID header. Implement when per-connector normaliser work lands.
 */
final class OutboundTitle {

    private OutboundTitle() {}

    static String forConnector(final String outboundConnectorId, final ChannelRef channel) {
        if ("email".equals(outboundConnectorId)) {
            return "Re: " + channel.name();
        }
        return null;
    }
}
