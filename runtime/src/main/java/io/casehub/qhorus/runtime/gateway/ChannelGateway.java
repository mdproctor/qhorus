package io.casehub.qhorus.runtime.gateway;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;

import io.casehub.ledger.api.model.ActorType;
import io.casehub.qhorus.api.gateway.*;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.message.MessageService;

@ApplicationScoped
public class ChannelGateway {

    private static final Logger LOG = Logger.getLogger(ChannelGateway.class);

    private final ConcurrentHashMap<UUID, List<BackendEntry>> registry = new ConcurrentHashMap<>();

    final AgentChannelBackend agentBackend;
    final InboundNormaliser normaliser;
    final MessageService messageService;

    @Inject
    public ChannelGateway(AgentChannelBackend agentBackend,
                          InboundNormaliser normaliser,
                          MessageService messageService) {
        this.agentBackend = agentBackend;
        this.normaliser = normaliser;
        this.messageService = messageService;
    }

    /** Called by create_channel to initialise the default backend registration. */
    public void initChannel(UUID channelId, ChannelRef ref) {
        registry.computeIfAbsent(channelId, id -> {
            List<BackendEntry> entries = Collections.synchronizedList(new ArrayList<>());
            agentBackend.open(ref, Map.of());
            entries.add(new BackendEntry(agentBackend, "agent"));
            return entries;
        });
    }

    /** Called by delete_channel to clean up all backends. */
    public void closeChannel(UUID channelId, ChannelRef ref) {
        List<BackendEntry> entries = registry.remove(channelId);
        if (entries != null) {
            for (BackendEntry e : entries) {
                try {
                    e.backend().close(ref);
                } catch (Exception ex) {
                    LOG.errorf(ex, "Error closing backend %s on channel %s",
                            e.backend().backendId(), channelId);
                }
            }
        }
    }

    public void registerBackend(UUID channelId, ChannelBackend backend, String backendType) {
        List<BackendEntry> entries = registry.computeIfAbsent(channelId,
                id -> Collections.synchronizedList(new ArrayList<>()));
        if ("human_participating".equals(backendType)) {
            entries.stream()
                    .filter(e -> "human_participating".equals(e.backendType()))
                    .findFirst()
                    .ifPresent(existing -> {
                        throw new DuplicateParticipatingBackendException(
                                channelId.toString(), existing.backend().backendId());
                    });
        }
        entries.add(new BackendEntry(backend, backendType));
    }

    public void deregisterBackend(UUID channelId, String backendId) {
        if ("qhorus-internal".equals(backendId)) {
            throw new IllegalArgumentException("Cannot deregister the qhorus-internal backend.");
        }
        List<BackendEntry> entries = registry.get(channelId);
        if (entries != null) {
            entries.removeIf(e -> backendId.equals(e.backend().backendId()));
        }
    }

    public List<BackendRegistration> listBackends(UUID channelId) {
        List<BackendEntry> entries = registry.getOrDefault(channelId, List.of());
        return entries.stream()
                .map(e -> new BackendRegistration(
                        e.backend().backendId(),
                        e.backendType(),
                        e.backend().actorType()))
                .toList();
    }

    /** Outbound — called by QhorusMcpTools.sendMessage(). */
    public void post(UUID channelId, OutboundMessage message) {
        ChannelRef ref = new ChannelRef(channelId, channelId.toString());
        // Source-of-truth write — synchronous, may throw
        agentBackend.post(ref, message);
        // Fan-out to external backends — async via virtual threads, failures non-fatal
        fanOut(channelId, message);
    }

    /**
     * Fan-out to external backends only (not QhorusChannelBackend).
     * Called after MessageService persists, so the agent backend is deliberately skipped.
     */
    public void fanOut(UUID channelId, OutboundMessage message) {
        ChannelRef ref = new ChannelRef(channelId, channelId.toString());
        List<BackendEntry> entries = registry.getOrDefault(channelId, List.of());
        for (BackendEntry entry : List.copyOf(entries)) {
            if (entry.backend() == agentBackend) continue;
            ChannelBackend backend = entry.backend();
            Thread.ofVirtual().start(() -> {
                try {
                    backend.post(ref, message);
                } catch (Exception ex) {
                    LOG.errorf(ex, "Backend %s failed on fanOut to channel %s",
                            backend.backendId(), channelId);
                }
            });
        }
    }

    /** Inbound from HumanParticipatingChannelBackend. */
    public void receiveHumanMessage(ChannelRef channel, InboundHumanMessage raw) {
        NormalisedMessage normalised = normaliser.normalise(channel, raw);
        messageService.send(channel.id(), normalised.senderInstanceId(),
                normalised.type(), normalised.content(), null, null);
    }

    /** Inbound from HumanObserverChannelBackend — always EVENT regardless of content. */
    public void receiveObserverSignal(ChannelRef channel, ObserverSignal signal) {
        messageService.send(channel.id(), "human:" + signal.externalSenderId(),
                MessageType.EVENT, signal.content(), null, null);
    }

    public record BackendEntry(ChannelBackend backend, String backendType) {}

    public record BackendRegistration(String backendId, String backendType, ActorType actorType) {}
}
