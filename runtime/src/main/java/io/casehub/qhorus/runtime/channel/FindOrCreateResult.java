package io.casehub.qhorus.runtime.channel;

import io.casehub.qhorus.api.channel.Channel;

public record FindOrCreateResult(Channel channel, boolean wasCreated) {}
