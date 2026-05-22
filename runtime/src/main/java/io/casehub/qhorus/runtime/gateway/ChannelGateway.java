package io.casehub.qhorus.runtime.gateway;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;

import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.gateway.*;
import io.casehub.qhorus.api.message.MessageDispatch;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.channel.Channel;
import io.casehub.qhorus.runtime.channel.ChannelService;
import io.casehub.qhorus.runtime.message.MessageService;
import io.quarkus.runtime.StartupEvent;

@ApplicationScoped
public class ChannelGateway {

    private static final Logger LOG = Logger.getLogger(ChannelGateway.class);

    private final ConcurrentHashMap<UUID, List<BackendEntry>> registry = new ConcurrentHashMap<>();

    final AgentChannelBackend agentBackend;
    final InboundNormaliser normaliser;
    final MessageService messageService;
    final ChannelService channelService;
    final Event<ChannelInitialisedEvent> channelInitialisedEvents;

    @Inject
    public ChannelGateway(AgentChannelBackend agentBackend,
                          InboundNormaliser normaliser,
                          MessageService messageService,
                          ChannelService channelService,
                          Event<ChannelInitialisedEvent> channelInitialisedEvents) {
        this.agentBackend = agentBackend;
        this.normaliser = normaliser;
        this.messageService = messageService;
        this.channelService = channelService;
        this.channelInitialisedEvents = channelInitialisedEvents;
    }

    /**
     * Initialises the gateway registry entry for a channel and fires
     * {@link ChannelInitialisedEvent} so external backends can register.
     * Called by create_channel and by the startup hook.
     * Idempotent — {@link ConcurrentHashMap#computeIfAbsent} ensures the agent
     * backend is added only once, but the event fires on every call.
     */
    public void initChannel(UUID channelId, ChannelRef ref) {
        registry.computeIfAbsent(channelId, id -> {
            List<BackendEntry> entries = Collections.synchronizedList(new ArrayList<>());
            agentBackend.open(ref, Map.of());
            entries.add(new BackendEntry(agentBackend, "agent"));
            return entries;
        });
        channelInitialisedEvents.fire(new ChannelInitialisedEvent(channelId, ref.name()));
    }

    /**
     * Restores gateway registry entries for all persisted channels on application startup.
     * Fires {@link ChannelInitialisedEvent} for each channel, giving external backends
     * the opportunity to re-register without implementing their own startup recovery.
     */
    void onStart(@Observes StartupEvent ev) {
        for (Channel ch : channelService.listAll()) {
            try {
                initChannel(ch.id, new ChannelRef(ch.id, ch.name));
            } catch (Exception ex) {
                LOG.errorf(ex, "Failed to initialise gateway registry for channel %s (%s) on startup",
                        ch.id, ch.name);
            }
        }
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
            synchronized (entries) {
                entries.stream()
                        .filter(e -> "human_participating".equals(e.backendType()))
                        .findFirst()
                        .ifPresent(existing -> {
                            throw new DuplicateParticipatingBackendException(
                                    channelId.toString(), existing.backend().backendId());
                        });
                entries.add(new BackendEntry(backend, backendType));
            }
        } else {
            entries.add(new BackendEntry(backend, backendType));
        }
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

    /**
     * For unit testing only — production code calls fanOut() after MessageService.send().
     * Do NOT call from production paths: agentBackend.post() would double-persist the message.
     */
    void post(UUID channelId, OutboundMessage message) {
        ChannelRef ref = new ChannelRef(channelId, channelId.toString());
        // Source-of-truth write — synchronous, may throw
        agentBackend.post(ref, message);
        // Fan-out to external backends — async via virtual threads, failures non-fatal
        fanOut(channelId, message);
    }

    /**
     * Fan-out to external backends after MessageService has persisted the message.
     * Called by QhorusMcpTools.sendMessage() after messageService.send() returns.
     * Does NOT call QhorusChannelBackend — persistence already happened.
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
        NormalisedMessage n = normaliser.normalise(channel, raw);
        // Use canonical constructor to bypass builder protocol validation —
        // inbound human messages may legitimately carry DONE/RESPONSE/etc. without inReplyTo
        // (the normaliser synthesises the type from human context, not from a prior message).
        messageService.dispatch(new MessageDispatch(
                channel.id(),
                n.senderInstanceId(),
                n.type(),
                n.content(),
                n.correlationId(),
                n.inReplyTo(),
                n.artefactRefs(),
                n.target(),
                null, // subjectId
                null, // causedByEntryId
                ActorType.HUMAN));
    }

    /** Inbound from HumanObserverChannelBackend — always EVENT regardless of content. */
    public void receiveObserverSignal(ChannelRef channel, ObserverSignal signal) {
        messageService.dispatch(MessageDispatch.builder()
                .channelId(channel.id())
                .sender("human:" + signal.externalSenderId())
                .type(MessageType.EVENT)
                .content(signal.content())
                .actorType(ActorType.HUMAN)
                .build());
    }

    record BackendEntry(ChannelBackend backend, String backendType) {}

    public record BackendRegistration(String backendId, String backendType, ActorType actorType) {}
}
