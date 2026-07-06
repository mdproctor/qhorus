package io.casehub.qhorus.api.store;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.api.message.Message;
import io.casehub.qhorus.api.store.query.MessageQuery;

/**
 * Read-only cross-tenant view of all messages across all tenancies.
 *
 * <p>Obtain via CDI injection:
 * <pre>{@code
 *   @Inject CrossTenantMessageStore store;
 * }</pre>
 *
 * <p>Refs #260.
 */
public interface CrossTenantMessageStore {

    /**
     * Scan messages across all tenancies matching the given query.
     * Callers should scope queries with explicit channelId or other
     * filters to avoid unbounded result sets.
     */
    List<Message> scan(MessageQuery query);

    /**
     * Count messages across all tenancies matching the given query.
     * Used by WatchdogEvaluationService.evaluateQueueDepth().
     */
    long count(MessageQuery query);

    /** Count messages in the given channel, regardless of tenancy. */
    int countByChannel(UUID channelId);

    /**
     * Distinct sender IDs that have posted to {@code channelId},
     * excluding messages of {@code excludedType}.
     *
     * @param channelId    the channel to query
     * @param excludedType message type to exclude from the sender scan
     *                     (e.g. {@code MessageType.EVENT} to skip telemetry senders)
     */
    List<String> distinctSendersByChannel(UUID channelId, MessageType excludedType);

    /**
     * The most recent message in {@code channelId} by insertion order,
     * or empty if the channel has no messages.
     */
    Optional<Message> findLastMessage(UUID channelId);

    /** Find a message by its primary key, regardless of tenancy. */
    Optional<Message> find(Long id);
}
