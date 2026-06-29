package io.casehub.qhorus.runtime.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/**
 * Delivery guarantee settings for channel backends with AT_LEAST_ONCE semantics.
 */
@ConfigMapping(prefix = "casehub.qhorus.delivery")
public interface DeliveryConfig {

    /**
     * Whether the delivery service is enabled. When false, all backends operate with
     * BEST_EFFORT semantics regardless of their declared deliveryGuarantee(). Default: true.
     */
    @WithDefault("true")
    boolean enabled();

    /**
     * Maximum number of messages to process in a single delivery batch. Default: 100.
     */
    @WithDefault("100")
    int batchSize();

    /**
     * Maximum consecutive delivery failures before a backend is marked as degraded.
     * Default: 10.
     */
    @WithDefault("10")
    int maxConsecutiveFailures();

    /**
     * Interval for reconciliation scans that detect and retry stalled deliveries.
     * Default: 30s.
     */
    @WithDefault("30s")
    String reconciliationInterval();
}
