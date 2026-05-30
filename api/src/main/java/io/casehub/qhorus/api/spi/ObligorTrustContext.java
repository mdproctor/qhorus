package io.casehub.qhorus.api.spi;

import java.util.UUID;

/**
 * Context passed to {@link ObligorTrustPolicy#permits} when a COMMAND message targets
 * a named obligor.
 *
 * <p>{@code channelId} is the stable key; {@code channelName} is the human-readable name
 * used by custom implementations that map channel names to trust dimensions (e.g. Claudony's
 * capability-scoped evaluation).
 *
 * <p>Refs #213.
 */
public record ObligorTrustContext(String obligorId, UUID channelId, String channelName) {}
