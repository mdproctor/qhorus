package io.casehub.qhorus.runtime.message.protocol;

import io.casehub.qhorus.api.spi.ChannelProtocol;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

@ApplicationScoped
public class ProtocolRegistry {

    private static final Logger LOG = Logger.getLogger(ProtocolRegistry.class);

    private final Map<String, ChannelProtocol> registry;

    @Inject
    ProtocolRegistry(@Any final Instance<ChannelProtocol> protocols) {
        this(buildMap(protocols));
    }

    ProtocolRegistry(final List<? extends ChannelProtocol> protocols) {
        this(buildMap(protocols));
    }

    private ProtocolRegistry(final Map<String, ChannelProtocol> registry) {
        this.registry = registry;
    }

    private static Map<String, ChannelProtocol> buildMap(
            final Iterable<? extends ChannelProtocol> protocols) {
        final Map<String, ChannelProtocol> map = new HashMap<>();
        for (final ChannelProtocol p : protocols) {
            final String name = p.protocolName();
            if (name == null || name.isBlank()) {
                throw new IllegalStateException(
                        p.getClass().getName() + ".protocolName() returned null or blank — "
                        + "each ChannelProtocol must return a non-null, non-empty name");
            }
            if (map.put(name, p) != null) {
                throw new IllegalStateException(
                        "Duplicate ChannelProtocol name '" + name + "' — "
                        + "each protocol must have a unique protocolName()");
            }
        }
        return Collections.unmodifiableMap(map);
    }

    public List<ChannelProtocol> forProtocols(final List<String> names) {
        final List<ChannelProtocol> result = new ArrayList<>(names.size());
        for (final String name : names) {
            final ChannelProtocol p = registry.get(name);
            if (p != null) {
                result.add(p);
            } else {
                LOG.warnf("Unknown protocol '%s' — skipped. Registered: %s", name, registry.keySet());
            }
        }
        return result;
    }

    public Set<String> allNames() {
        return Collections.unmodifiableSet(new TreeSet<>(registry.keySet()));
    }
}
