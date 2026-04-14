package io.quarkiverse.qhorus.api;

import java.util.Map;

import io.quarkus.test.junit.QuarkusTestProfile;

/** Test profile that enables the watchdog module for integration/e2e tests. */
public class WatchdogEnabledProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of("quarkus.qhorus.watchdog.enabled", "true");
    }
}
