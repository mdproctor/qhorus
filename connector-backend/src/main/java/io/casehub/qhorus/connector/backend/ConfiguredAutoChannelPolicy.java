package io.casehub.qhorus.connector.backend;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.casehub.connectors.InboundConnectorIds;
import io.casehub.connectors.InboundMessage;
import io.casehub.connectors.twilio.TwilioSmsConnector;
import io.casehub.connectors.whatsapp.WhatsAppConnector;
import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.casehub.qhorus.api.channel.ChannelSlugValidator;
import io.quarkus.arc.DefaultBean;
import org.jboss.logging.Logger;

@DefaultBean
@ApplicationScoped
class ConfiguredAutoChannelPolicy implements AutoChannelPolicy {

    private static final Logger LOG = Logger.getLogger(ConfiguredAutoChannelPolicy.class);

    // Convention table: protocol-coupled connectors where inbound and outbound
    // must use the same provider (SMS threading rules, WhatsApp API contract).
    private static final Map<String, String> OUTBOUND_CONVENTION = Map.of(
            InboundConnectorIds.TWILIO_SMS, TwilioSmsConnector.ID,
            InboundConnectorIds.WHATSAPP,   WhatsAppConnector.ID
    );

    private final ConnectorAutoChannelConfig config;

    @Inject
    ConfiguredAutoChannelPolicy(ConnectorAutoChannelConfig config) {
        this.config = config;
    }

    @PostConstruct
    void validateConfiguredPatterns() {
        config.entries().forEach((connectorId, entry) ->
            entry.channelNamePattern().ifPresent(ConfiguredAutoChannelPolicy::validatePattern));
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
                .map(p -> p.replace("{connectorId}", slugifyConnectorId(msg.connectorId()))
                            .replace("{lookupKey}", sanitiseSegment(lookupKey)))
                .orElse("connector/" + slugifyConnectorId(msg.connectorId()) + "/" + sanitiseSegment(lookupKey));

        ChannelSemantic semantic = entry.semantic()
                .map(s -> ChannelSemantic.valueOf(s.toUpperCase()))
                .orElse(ChannelSemantic.APPEND);

        String description = "Auto-created on first contact via " + msg.connectorId()
                + " from " + lookupKey;

        return Optional.of(new AutoChannelSpec(
                channelName, description, semantic, null, null,
                outboundConnectorId, outboundDestination));
    }

    /**
     * Normalises a connector ID to a slug segment without appending a hash.
     * Connector IDs are developer-defined controlled strings — they should be
     * valid slugs already. This function is defensive normalisation only.
     * Two non-conformant IDs that slugify identically share the same segment;
     * connector IDs that require slugification should be made unique at source.
     */
    static String slugifyConnectorId(String connectorId) {
        if (connectorId == null || connectorId.isBlank()) {
            throw new IllegalArgumentException("Connector ID must not be null or blank");
        }
        String lower = connectorId.toLowerCase(Locale.ROOT);
        String slug = lower.replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (slug.isEmpty()) {
            throw new IllegalArgumentException(
                    "Connector ID '" + connectorId + "' reduced to empty after slugification");
        }
        if (Character.isDigit(slug.charAt(0))) {
            slug = "id-" + slug;
        }
        if (slug.length() > 80) {
            slug = slug.substring(0, 80).replaceAll("-+$", "");
        }
        if (slug.isEmpty()) {
            throw new IllegalArgumentException(
                    "Connector ID '" + connectorId + "' produced empty slug after truncation");
        }
        return slug;
    }

    /**
     * Sanitises an arbitrary external identifier (phone number, email, etc.) to a
     * slug segment, always appending an 8-hex-char SHA-256 hash of the lowercased
     * input. The hash is unconditional — it guarantees uniqueness even when two
     * different raw inputs produce the same sanitised prefix.
     *
     * <p>Hash is of the lowercased form so case variants (e.g. user@example.com
     * and User@Example.COM) map to the same channel.
     */
    static String sanitiseSegment(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Cannot sanitise null or blank segment");
        }
        String lowercased = raw.toLowerCase(Locale.ROOT);
        String slug = lowercased.replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (slug.isEmpty()) {
            throw new IllegalArgumentException(
                    "Segment '" + raw + "' reduced to empty after sanitisation");
        }
        if (Character.isDigit(slug.charAt(0))) {
            slug = "id-" + slug;
        }
        if (slug.length() > 71) {
            slug = slug.substring(0, 71).replaceAll("-+$", "");
        }
        if (slug.isEmpty()) {
            throw new IllegalArgumentException(
                    "Segment '" + raw + "' produced empty slug after truncation");
        }
        return slug + "-" + hashHex8(lowercased);
    }

    /** Validates that every literal segment in a channel name pattern is a valid slug segment. */
    static void validatePattern(String pattern) {
        for (String segment : pattern.split("/", -1)) {
            String testable = segment.replaceAll("\\{[^}]+}", "a");
            if (!ChannelSlugValidator.isValidSegment(testable)) {
                throw new IllegalStateException(
                        "Channel name pattern '" + pattern + "' has invalid literal segment '"
                        + segment + "' — literal parts must match [a-z][a-z0-9]*(-[a-z0-9]+)*");
            }
        }
    }

    private static String hashHex8(String lowercased) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(lowercased.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 4); // 4 bytes = 8 hex chars
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
