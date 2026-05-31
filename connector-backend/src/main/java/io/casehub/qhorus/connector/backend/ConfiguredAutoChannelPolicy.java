package io.casehub.qhorus.connector.backend;

import java.util.Map;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.casehub.connectors.InboundConnectorIds;
import io.casehub.connectors.InboundMessage;
import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.quarkus.arc.DefaultBean;
import org.jboss.logging.Logger;

@DefaultBean
@ApplicationScoped
class ConfiguredAutoChannelPolicy implements AutoChannelPolicy {

    private static final Logger LOG = Logger.getLogger(ConfiguredAutoChannelPolicy.class);

    // Convention table: protocol-coupled connectors where inbound and outbound
    // must use the same provider (SMS threading rules, WhatsApp API contract).
    private static final Map<String, String> OUTBOUND_CONVENTION = Map.of(
            InboundConnectorIds.TWILIO_SMS, "twilio-sms",
            InboundConnectorIds.WHATSAPP,   "whatsapp"
    );

    private final ConnectorAutoChannelConfig config;

    @Inject
    ConfiguredAutoChannelPolicy(ConnectorAutoChannelConfig config) {
        this.config = config;
    }

    @Override
    public Optional<AutoChannelSpec> onFirstContact(InboundMessage msg, String lookupKey) {
        ConnectorAutoChannelConfig.ConnectorAutoChannelEntry entry =
                config.entries().get(msg.connectorId());
        if (entry == null || !entry.enabled()) {
            return Optional.empty();
        }

        String outboundConnectorId = entry.outboundConnectorId()
                .or(() -> Optional.ofNullable(OUTBOUND_CONVENTION.get(msg.connectorId())))
                .orElse(null);

        if (outboundConnectorId == null) {
            LOG.errorf("auto-channel enabled for connector '%s' but no outbound-connector-id " +
                       "configured and no convention applies — add casehub.qhorus.connector." +
                       "auto-channel.entries.\"%s\".outbound-connector-id to application.properties",
                       msg.connectorId(), msg.connectorId());
            return Optional.empty();
        }

        String outboundDestination = ConnectorKeyStrategy.deriveKey(msg);

        String channelName = entry.channelNamePattern()
                .map(p -> p.replace("{connectorId}", msg.connectorId())
                            .replace("{lookupKey}", lookupKey))
                .orElse("connector/" + msg.connectorId() + "/" + lookupKey);

        ChannelSemantic semantic = entry.semantic()
                .map(ChannelSemantic::valueOf)
                .orElse(ChannelSemantic.APPEND);

        String description = "Auto-created on first contact via " + msg.connectorId()
                + " from " + lookupKey;

        return Optional.of(new AutoChannelSpec(
                channelName, description, semantic, null,
                outboundConnectorId, outboundDestination));
    }
}
