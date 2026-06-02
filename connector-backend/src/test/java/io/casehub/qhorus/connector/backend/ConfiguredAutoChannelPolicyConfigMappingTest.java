package io.casehub.qhorus.connector.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.casehub.connectors.InboundConnectorIds;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;

/**
 * Smoke test confirming that {@code @ConfigMapping(prefix = "casehub.qhorus.connector.auto-channel")}
 * with {@code Map<String, ConnectorAutoChannelEntry>} and hyphenated map keys (e.g.
 * {@code "twilio-sms-inbound"}) is accepted by SmallRye Config at startup and wired
 * correctly to {@link ConfiguredAutoChannelPolicy}.
 *
 * <p>The unit test {@link ConfiguredAutoChannelPolicyTest} mocks the config; this test exercises
 * the real config pipeline. Refs qhorus#226.
 */
@QuarkusTest
@TestProfile(ConfiguredAutoChannelPolicyConfigMappingTest.Profile.class)
class ConfiguredAutoChannelPolicyConfigMappingTest {

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "casehub.qhorus.connector.auto-channel.entries.\"twilio-sms-inbound\".enabled", "true"
            );
        }
    }

    @Inject
    ConnectorAutoChannelConfig autoChannelConfig;

    @Inject
    ConfiguredAutoChannelPolicy policy;

    @Test
    void configMapping_startupSucceeds_noConfigurationException() {
        assertThat(policy).isNotNull();
    }

    @Test
    void configMapping_hyphenatedKey_resolvedToConnectorId() {
        assertThat(autoChannelConfig.entries()).containsKey(InboundConnectorIds.TWILIO_SMS);
        assertThat(autoChannelConfig.entries().get(InboundConnectorIds.TWILIO_SMS).enabled()).isTrue();
    }
}
