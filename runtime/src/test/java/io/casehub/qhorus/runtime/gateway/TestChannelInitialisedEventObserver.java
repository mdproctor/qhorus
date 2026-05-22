package io.casehub.qhorus.runtime.gateway;

import java.util.ArrayList;
import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

import io.casehub.qhorus.api.gateway.ChannelInitialisedEvent;

@ApplicationScoped
class TestChannelInitialisedEventObserver {

    private final List<ChannelInitialisedEvent> captured = new ArrayList<>();

    void onEvent(@Observes ChannelInitialisedEvent event) {
        captured.add(event);
    }

    List<ChannelInitialisedEvent> events() {
        return List.copyOf(captured);
    }

    void clear() {
        captured.clear();
    }
}
