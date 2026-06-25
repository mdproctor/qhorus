package io.casehub.qhorus.api.gateway;

import java.util.UUID;

/**
 * At most one per channel. Full speech act inbound via {@link InboundNormaliser}.
 * {@code actorType()} must return {@code ActorType.HUMAN}.
 * Call {@code gateway.receiveHumanMessage()} when inbound arrives.
 *
 * <p>{@code post()} must catch all exceptions internally — failure is non-fatal;
 * the gateway logs and continues.
 *
 * <p>Override {@link #normaliserFor(UUID)} to provide per-channel type inference.
 * Return {@code null} (the default) to use the system {@code DefaultInboundNormaliser}.
 */
public interface HumanParticipatingChannelBackend extends ChannelBackend {

    /**
     * Returns the {@link InboundNormaliser} for messages received from this backend
     * on the given channel, or {@code null} to use the system default normaliser.
     *
     * <p>The normaliser converts raw prose input (an {@link InboundHumanMessage}) into
     * a typed {@link NormalisedMessage}. Backends that know the message type and
     * reply context (e.g. a UI with explicit Reply / New Message controls) should
     * override this and return a normaliser that reads from the message's fields
     * rather than inferring from metadata.
     *
     * <p><strong>Parameter is UUID, not ChannelRef:</strong> This method is called
     * during backend registration before the full Channel entity is available. Using
     * UUID avoids a circular dependency where registration needs the normaliser before
     * the channel is fully initialized.
     *
     * @param channelId the channel UUID
     * @return normaliser for this channel, or {@code null} for default
     */
    default InboundNormaliser normaliserFor(UUID channelId) { return null; }
}
