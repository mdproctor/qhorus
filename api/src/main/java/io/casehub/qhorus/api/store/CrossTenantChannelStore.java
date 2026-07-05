package io.casehub.qhorus.api.store;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.casehub.qhorus.api.channel.Channel;

/**
 * Read-only cross-tenant view of all channels across all tenancies.
 *
 * <p>Obtain via CDI injection:
 * <pre>{@code
 *   @Inject CrossTenantChannelStore store;
 * }</pre>
 *
 * <p>Refs #260.
 */
public interface CrossTenantChannelStore {

    /** All channels across all tenancies. */
    List<Channel> listAll();

    /** Find a channel by its UUID, regardless of tenancy. */
    Optional<Channel> findById(UUID id);

    /**
     * Find a channel by name within a specific tenancy.
     * Used by WatchdogEvaluationService to locate notification channels
     * when evaluating conditions that span tenant boundaries.
     *
     * @param name      the channel slug name
     * @param tenancyId the tenancy scope to search within
     * @return the matching channel, or empty if not found
     */
    Optional<Channel> findByNameAndTenancy(String name, String tenancyId);
}
