package io.quarkiverse.qhorus.runtime.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

@ConfigMapping(prefix = "quarkus.qhorus")
public interface QhorusConfig {

    /** Cleanup and data retention settings. */
    Cleanup cleanup();

    interface Cleanup {

        /** How long (seconds) before an instance is considered stale. Default: 120. */
        @WithDefault("120")
        int staleInstanceSeconds();

        /** Days to retain old messages and shared data before purging. Default: 7. */
        @WithDefault("7")
        int dataRetentionDays();
    }
}
