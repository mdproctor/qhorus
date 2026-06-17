package io.casehub.qhorus.runtime.config;

import java.util.List;
import java.util.Optional;

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;

@ConfigMapping(prefix = "casehub.qhorus")
@ConfigRoot(phase = ConfigPhase.RUN_TIME)
public interface QhorusConfig {

    /**
     * Reactive service tier settings.
     *
     * <p>The {@code enabled} flag is a BUILD_TIME property (see {@code QhorusBuildTimeConfig}).
     * This runtime mapping exists solely to prevent SmallRye Config {@code SRCFG00050} when
     * the property is present in a system property source (e.g. set via a
     * {@code QuarkusTestProfile.getConfigOverrides()} call). The runtime value is unused —
     * CDI bean selection is governed by the build-time augmentation result.
     */
    Reactive reactive();

    /** Cleanup and data retention settings. */
    Cleanup cleanup();

    /** Agent Card fields served at /.well-known/agent-card.json. */
    AgentCard agentCard();

    /** A2A compatibility endpoint settings. */
    A2a a2a();

    /** Watchdog alerting settings (optional module). */
    Watchdog watchdog();

    /** Attestation confidence values written on terminal commitment outcomes. */
    Attestation attestation();

    /** Trust gate and obligation settings. */
    Commitment commitment();

    interface Commitment {
        /**
         * Minimum trust score (0.0–1.0) an obligor must have for a COMMAND to be accepted.
         * 0.0 (default) disables the gate — all existing behaviour is preserved.
         *
         * <p>WARNING: agents with no trust history in ActorTrustScore are rejected when this
         * is > 0.0. Pre-seed trust scores for all agents before enabling this gate.
         */
        @WithDefault("0.0")
        double minObligorTrust();
    }

    interface Watchdog {
        /**
         * When true, enables the watchdog MCP tools and condition evaluation scheduler.
         * Disabled by default — opt-in.
         */
        @WithDefault("false")
        boolean enabled();

        /** Interval in seconds between watchdog evaluation runs. Default: 60. */
        @WithDefault("60")
        int checkIntervalSeconds();

        /** Alert dispatch configuration — connectors and destinations for watchdog notifications. */
        Alert alert();

        interface Alert {
            /** Alert endpoint destinations. Empty by default — no external alerts dispatched. */
            @WithDefault("")
            List<AlertEndpoint> endpoints();

            interface AlertEndpoint {
                /** The connector bean ID to use for dispatching this alert. */
                @WithName("connector-id")
                String connectorId();
                /** The destination address or topic on the connector (e.g. channel name, webhook URL). */
                String destination();
            }
        }
    }

    interface A2a {
        /**
         * When true, exposes A2A-compatible REST endpoints at /a2a/*.
         * Disabled by default — opt-in to avoid unintended exposure.
         */
        @WithDefault("false")
        boolean enabled();

        /** SSE stream settings for the A2A streaming endpoint. */
        SseSettings sse();

        interface SseSettings {
            /** Interval between SSE keepalive events (event: keepalive). Default: 15s. */
            @WithDefault("15")
            int heartbeatIntervalSeconds();

            /**
             * Maximum SSE stream lifetime before server-side close. Default: 1800s (30 min).
             *
             * A2A tasks in multi-agent coordination routinely run for minutes to hours.
             * 1800s is the defensible floor — long enough for coordinated agent tasks,
             * short enough to bound runaway streams. Increase for longer-running tasks.
             */
            @WithDefault("1800")
            int maxDurationSeconds();
        }
    }

    interface AgentCard {
        /** Display name of this Qhorus deployment. */
        @WithDefault("Qhorus Agent Mesh")
        String name();

        /** Human-readable description of what this deployment provides. */
        @WithDefault("Peer-to-peer agent communication mesh — channels, messages, shared data, presence")
        String description();

        /** Public URL of this deployment. Should be set per-deployment; absent when not configured. */
        Optional<String> url();

        /** Version of this Qhorus deployment. */
        @WithDefault("1.0.0")
        String version();
    }

    /**
     * Runtime shadow of the build-time reactive.enabled flag.
     *
     * <p>Exists only to satisfy SmallRye Config's runtime property validation — prevents
     * {@code SRCFG00050} when {@code casehub.qhorus.reactive.enabled=true} is passed via
     * a test profile's {@code getConfigOverrides()} (which populates SysPropConfigSource).
     * The actual CDI bean selection is determined at augmentation time by
     * {@code QhorusBuildTimeConfig.reactive().enabled()}.
     */
    interface Reactive {
        /**
         * Whether the reactive service tier is active.
         *
         * <p>This runtime value is informational only — bean selection is fixed at build time.
         * Default: {@code false}.
         */
        @WithDefault("false")
        boolean enabled();
    }

    interface Cleanup {

        /** How long (seconds) before an instance is considered stale. Default: 120. */
        @WithDefault("120")
        int staleInstanceSeconds();

        /** Days to retain old messages and shared data before purging. Default: 7. */
        @WithDefault("7")
        int dataRetentionDays();
    }

    interface Attestation {
        /**
         * Confidence score (0.0–1.0) for the SOUND attestation written when a DONE message
         * closes a COMMAND commitment. Default: 0.7.
         */
        @WithDefault("0.7")
        double doneConfidence();

        /**
         * Confidence score (0.0–1.0) for the FLAGGED attestation written when a FAILURE
         * message closes a COMMAND commitment. Default: 0.6.
         */
        @WithDefault("0.6")
        double failureConfidence();

        /**
         * Confidence score (0.0–1.0) for the FLAGGED attestation written when a DECLINE
         * message closes a COMMAND commitment. Default: 0.4.
         */
        @WithDefault("0.4")
        double declineConfidence();
    }
}
