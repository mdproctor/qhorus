package io.casehub.qhorus.connector.backend;

import io.casehub.qhorus.api.gateway.InboundNormaliser;

/**
 * A normaliser scoped to a specific inbound connector.
 *
 * <p>Implementations are {@code @ApplicationScoped} CDI beans discovered by
 * {@link ConnectorChannelBackend} at startup and dispatched based on the
 * channel's connector binding.
 *
 * <p>Keyed on connector ID (not connector type) to match the existing
 * {@link ConnectorKeyStrategy} pattern and the binding's
 * {@code inboundConnectorId} field.
 */
public interface ConnectorNormaliser extends InboundNormaliser {

    /**
     * The connector ID this normaliser handles (e.g. {@code "email-inbound"}).
     * Must match a value from {@link io.casehub.connectors.InboundConnectorIds}.
     */
    String connectorId();
}
