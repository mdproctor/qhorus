package io.casehub.qhorus.runtime.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;

import java.util.List;
import java.util.Optional;

@ConfigMapping(prefix = "casehub.qhorus")
public interface QhorusConfig {

    Cleanup cleanup();
    AgentCard agentCard();
    A2a a2a();
    Watchdog watchdog();
    Attestation attestation();
    Commitment commitment();
    Summary summary();
    Protocol protocol();
    Routing routing();
    ConnectorBackend connectorBackend();
    Correction correction();

    interface ConnectorBackend {
        Optional<String> deliveryChannel();
    }

    interface Routing {
        @WithDefault("0.0")
        double defaultTrustThreshold();
        java.util.Optional<Double> defaultCapacityThreshold();
    }

    interface Summary {
        @WithDefault("true")
        boolean enabled();
        @WithDefault("60")
        int checkIntervalSeconds();
    }

    interface Protocol {
        @WithDefault("50")
        int lookbackSize();
        RequestResponse requestResponse();
        TaskCompletion taskCompletion();
        ContributionRequired contributionRequired();

        interface RequestResponse {
            @WithDefault("3")
            int maxOpenQueries();
        }
        interface TaskCompletion {
            @WithDefault("3")
            int maxOpenCommands();
        }
        interface ContributionRequired {
            @WithDefault("2")
            int maxConsecutive();
        }
    }

    interface Commitment {
        @WithDefault("0.0")
        double minObligorTrust();
        Optional<java.time.Duration> defaultQueryDeadline();
        Optional<java.time.Duration> defaultProposeDeadline();
    }

    interface Watchdog {
        @WithDefault("false")
        boolean enabled();
        @WithDefault("60")
        int checkIntervalSeconds();
        Alert alert();

        interface Alert {
            @WithDefault("")
            List<AlertEndpoint> endpoints();
            interface AlertEndpoint {
                @WithName("connector-id")
                String connectorId();
                String destination();
            }
        }
    }

    interface A2a {
        @WithDefault("false")
        boolean enabled();
        @WithDefault("false")
        boolean pushToEventBroadcaster();
        SseSettings sse();
        interface SseSettings {
            @WithDefault("15")
            int heartbeatIntervalSeconds();
            @WithDefault("1800")
            int maxDurationSeconds();
        }
    }

    interface AgentCard {
        @WithDefault("Qhorus Agent Mesh")
        String name();
        @WithDefault("Peer-to-peer agent communication mesh — channels, messages, shared data, presence")
        String description();
        Optional<String> url();
        @WithDefault("1.0.0")
        String version();
    }

    interface Cleanup {
        @WithDefault("120")
        int staleInstanceSeconds();
        @WithDefault("7")
        int dataRetentionDays();
    }

    interface Attestation {
        @WithDefault("0.7")
        double doneConfidence();
        @WithDefault("0.6")
        double failureConfidence();
        @WithDefault("0.4")
        double declineConfidence();
        @WithDefault("0.3")
        double responseConfidence();
        @WithDefault("0.7")
        double judgmentAcceptedConfidence();
        @WithDefault("0.3")
        double judgmentRejectedConfidence();
        @WithDefault("0.5")
        double judgmentPartialConfidence();
        @WithDefault("0.4")
        double peerEndorsedConfidence();
        @WithDefault("0.5")
        double peerChallengedConfidence();
        @WithDefault("5")
        int credibilityMinDataPoints();
        @WithDefault("0.3")
        double credibilityLowAgreementThreshold();
        @WithDefault("false")
        boolean collusionDetectionEnabled();
        @WithDefault("0.8")
        double collusionThreshold();
    }

    interface Correction {
        @WithDefault("10")
        @WithName("max-per-message")
        int maxPerMessage();
    }
}
