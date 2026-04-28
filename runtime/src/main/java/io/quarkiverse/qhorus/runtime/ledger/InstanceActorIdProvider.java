package io.quarkiverse.qhorus.runtime.ledger;

/**
 * Maps a Qhorus {@code instanceId} (session-scoped, e.g. {@code claudony-worker-abc123}) to
 * a ledger {@code actorId} (persona-scoped, e.g. {@code claude:analyst@v1}).
 *
 * <p>
 * Called in {@link LedgerWriteService#record} and {@link ReactiveLedgerWriteService#record}
 * before writing {@code entry.actorId}. The resolved ID is also passed to
 * {@link CommitmentAttestationPolicy} so DONE attestations carry the persona-scoped attestorId.
 *
 * <p>
 * Default implementation ({@link DefaultInstanceActorIdProvider}) is a no-op identity function.
 * Replace with {@code @Alternative @Priority} to provide session→persona mapping (e.g. in
 * Claudony, which knows the session-to-persona mapping from {@code SessionRegistry}).
 *
 * <p>
 * Refs #124.
 */
@FunctionalInterface
public interface InstanceActorIdProvider {

    /**
     * Resolve a Qhorus instanceId to a ledger actorId.
     * Return the instanceId unchanged if no mapping is known. Never return null.
     *
     * @param instanceId the Qhorus instance identifier (e.g. {@code message.sender})
     * @return the ledger actorId to use; never null
     */
    String resolve(String instanceId);
}
