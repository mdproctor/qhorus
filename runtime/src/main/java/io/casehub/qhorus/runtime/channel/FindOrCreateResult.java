package io.casehub.qhorus.runtime.channel;

/**
 * Return value from {@link ChannelService#findOrCreateWithBinding}, distinguishing
 * a newly created channel from one that already existed in the binding store.
 *
 * <p>{@code wasCreated == true} means the call created a new channel and binding.
 * {@code wasCreated == false} means the binding existed before this call — the
 * channel was found on the recheck under {@code REQUIRES_NEW}. Callers must not
 * increment creation metrics on a {@code false} result. Refs qhorus#248.
 */
public record FindOrCreateResult(Channel channel, boolean wasCreated) {}
