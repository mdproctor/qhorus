package io.casehub.qhorus.deployment;

import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.nativeimage.NativeImageResourcePatternsBuildItem;

/**
 * Quarkus build-time processor for the Qhorus extension.
 *
 * <p>
 * Reactive vs blocking stack selection is governed by
 * {@code casehub.qhorus.reactive.enabled} declared as a
 * {@code BUILD_TIME} property in {@link QhorusBuildTimeConfig}.
 * Per-bean {@code @IfBuildProperty} / {@code @UnlessBuildProperty} annotations
 * on runtime beans use this property to include or exclude the appropriate stack.
 * JDBC-only consumers omit the property (default {@code false}) and get only the
 * blocking beans; reactive consumers set it to {@code true} in their
 * {@code application.properties} at build time.
 */
class QhorusProcessor {

    private static final String FEATURE = "qhorus";

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    @BuildStep
    NativeImageResourcePatternsBuildItem registerMigrationResources() {
        return NativeImageResourcePatternsBuildItem.builder()
                .includeGlob("db/qhorus/migration/*.sql")
                .includeGlob("db/ledger/migration/*.sql")
                .build();
    }
}
