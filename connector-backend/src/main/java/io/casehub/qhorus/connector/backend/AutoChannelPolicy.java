package io.casehub.qhorus.connector.backend;

import java.util.Optional;

import io.casehub.connectors.InboundMessage;

/**
 * SPI — determines whether and how to auto-create a Qhorus channel on first contact
 * from an unknown connector sender.
 *
 * <p>The {@code @DefaultBean} implementation is {@link ConfiguredAutoChannelPolicy},
 * which reads per-connector config. Displace it by providing your own
 * {@code @ApplicationScoped} bean in the consuming application.
 *
 * <p>Placed in {@code connector-backend} (not {@code api/spi/}) because the parameter
 * type {@code InboundMessage} comes from {@code casehub-connectors-core}, which the
 * {@code api} module does not depend on.
 *
 * @see AutoChannelSpec
 */
public interface AutoChannelPolicy {
    /**
     * @param msg        the inbound message that triggered the first-contact path
     * @param lookupKey  the key used for binding lookup (derived by ConnectorKeyStrategy);
     *                   becomes the channel's {@code externalKey} in the binding
     * @return the channel spec to create, or empty to fall back to discard
     */
    Optional<AutoChannelSpec> onFirstContact(InboundMessage msg, String lookupKey);
}
