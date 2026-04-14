package io.quarkiverse.qhorus.api;

import java.util.Map;

import io.quarkus.test.junit.QuarkusTestProfile;

/**
 * Test profile that enables the A2A endpoint for integration/e2e tests.
 * Used by @TestProfile on A2A test classes that exercise the enabled path.
 */
public class A2AEnabledProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of("quarkus.qhorus.a2a.enabled", "true");
    }
}
