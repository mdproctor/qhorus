package io.casehub.qhorus.api.store;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import io.casehub.qhorus.api.message.Commitment;

/**
 * Cross-tenant view of commitments, used for platform-wide obligation management.
 *
 * <p>Obtain via CDI injection with {@code @CrossTenant}:
 * <pre>{@code
 *   @Inject @CrossTenant CrossTenantCommitmentStore store;
 * }</pre>
 *
 * <p>Refs #260.
 */
public interface CrossTenantCommitmentStore {

    /** All OPEN or ACKNOWLEDGED commitments across every tenancy, sorted oldest first. */
    List<Commitment> findAllOpen();

    /** All non-terminal commitments in the given channel, regardless of tenancy. */
    List<Commitment> findOpenByChannel(UUID channelId);

    /**
     * Expire overdue commitments across all tenancies whose {@code expiresAt}
     * is strictly before {@code cutoff}.
     *
     * <p>Implementations should transition matching OPEN/ACKNOWLEDGED commitments
     * to EXPIRED state and record the transition timestamp.
     *
     * @param cutoff the expiry boundary; commitments with expiresAt before this are expired
     */
    void expireOverdue(Instant cutoff);
}
