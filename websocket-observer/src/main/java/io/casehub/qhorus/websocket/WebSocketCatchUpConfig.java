package io.casehub.qhorus.websocket;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

@ConfigMapping(prefix = "casehub.qhorus.websocket.catchup")
public interface WebSocketCatchUpConfig {

    @WithDefault("500")
    int maxMessages();
}
