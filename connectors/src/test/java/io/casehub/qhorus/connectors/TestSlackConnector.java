package io.casehub.qhorus.connectors;

import io.casehub.connectors.Connector;
import io.casehub.connectors.ConnectorMessage;
import io.quarkus.test.Mock;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.concurrent.CopyOnWriteArrayList;

@Mock
@ApplicationScoped
public class TestSlackConnector implements Connector {

    public static final CopyOnWriteArrayList<ConnectorMessage> sent = new CopyOnWriteArrayList<>();

    public static void clear() {
        sent.clear();
    }

    @Override
    public String id() {
        return "slack";
    }

    @Override
    public boolean send(ConnectorMessage message) {
        sent.add(message);
        return true;
    }
}
