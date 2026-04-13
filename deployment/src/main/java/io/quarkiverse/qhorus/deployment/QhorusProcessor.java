package io.quarkiverse.qhorus.deployment;

import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.FeatureBuildItem;

/**
 * Quarkus build-time processor for the Qhorus extension.
 * Registers the "qhorus" feature so it appears in the startup log:
 *   INFO  features: [cdi, hibernate-orm, qhorus, rest, ...]
 *
 * Additional @BuildStep methods will be added here as the extension matures:
 * - Native image reflection configuration
 * - Bean validation for @ConfigRoot(prefix = "quarkus.qhorus")
 * - Health check registration
 * - Flyway migration resource registration for native builds
 */
class QhorusProcessor {

    private static final String FEATURE = "qhorus";

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }
}
