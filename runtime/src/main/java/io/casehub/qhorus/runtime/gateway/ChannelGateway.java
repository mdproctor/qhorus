package io.casehub.qhorus.runtime.gateway;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import io.casehub.qhorus.api.gateway.DeliveryGuarantee;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;

import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.gateway.*;
import io.casehub.qhorus.api.message.MessageDispatch;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.api.qualifier.CrossTenant;
import java.util.Objects;
import io.casehub.qhorus.api.channel.Channel;
import io.casehub.qhorus.runtime.channel.ChannelService;
import io.casehub.qhorus.runtime.config.DeliveryConfig;
import io.casehub.qhorus.runtime.message.MessageService;
import io.casehub.qhorus.api.store.CrossTenantChannelStore;
import io.quarkus.runtime.StartupEvent;

@ApplicationScoped
public class ChannelGateway {

    private static final Logger LOG = Logger.getLogger(ChannelGateway.class);

    private final ConcurrentHashMap<UUID, List<BackendEntry>> registry = new ConcurrentHashMap<>();

    final AgentChannelBackend agentBackend;
    final InboundNormaliser normaliser;
    final MessageService messageService;
    final ChannelService channelService;
    final CrossTenantChannelStore crossTenantChannelStore;
    final Event<ChannelInitialisedEvent> channelInitialisedEvents;
    final DeliveryConfig deliveryConfig;

    @Inject
    public ChannelGateway(AgentChannelBackend agentBackend,
                          InboundNormaliser normaliser,
                          MessageService messageService,
                          ChannelService channelService,
                          @CrossTenant CrossTenantChannelStore crossTenantChannelStore,
                          Event<ChannelInitialisedEvent> channelInitialisedEvents,
                          DeliveryConfig deliveryConfig) {
        this.agentBackend = agentBackend;
        this.normaliser = normaliser;
        this.messageService = messageService;
        this.channelService = channelService;
        this.crossTenantChannelStore = crossTenantChannelStore;
        this.channelInitialisedEvents = channelInitialisedEvents;
        this.deliveryConfig = deliveryConfig;
    }

    /**
     * Initialises the gateway registry entry for a channel and fires
     * {@link ChannelInitialisedEvent} so external backends can register.
     * Called by create_channel and by the startup hook. Fires with {@code recovered=false}.
     * Idempotent — {@link ConcurrentHashMap#computeIfAbsent} ensures the agent
     * backend is added only once, but the event fires on every call.
     */
    public void initChannel(UUID channelId, ChannelRef ref) {
        initChannel(channelId, ref, false);
    }

    /**
     * As {@link #initChannel(UUID, ChannelRef)} but passes the {@code recovered} flag
     * through to {@link ChannelInitialisedEvent}. Set {@code recovered=true} when called
     * during startup recovery so observers can distinguish first-init from re-registration.
     * Refs #183.
     */
    public void initChannel(UUID channelId, ChannelRef ref, boolean recovered) {
        registry.computeIfAbsent(channelId, id -> {
            List<BackendEntry> entries = Collections.synchronizedList(new ArrayList<>());
            agentBackend.open(ref, Map.of());
            entries.add(new BackendEntry(agentBackend, "agent", null));
            return entries;
        });
        channelInitialisedEvents.fire(new ChannelInitialisedEvent(channelId, ref.name(), recovered));
    }

    /**
     * Restores gateway registry entries for all persisted channels on application startup.
     * Fires {@link ChannelInitialisedEvent} with {@code recovered=true} for each channel,
     * giving external backends the opportunity to re-register without implementing their
     * own startup recovery.
     */
    void onStart(@Observes StartupEvent ev) {
        for (Channel ch : crossTenantChannelStore.listAll()) {
            try {
                initChannel(ch.id(), new ChannelRef(ch.id(), ch.name()), true);
            } catch (Exception ex) {
                LOG.errorf(ex, "Failed to initialise gateway registry for channel %s (%s) on startup",
                        ch.id(), ch.name());
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
        InboundNormaliser backendNormaliser = (backend instanceof HumanParticipatingChannelBackend hb)
                ? hb.normaliserFor(channelId) : null;
        synchronized (entries) {
            // Dedup: same backendId already registered → no-op (idempotent re-registration)
            if (entries.stream().anyMatch(e -> backend.backendId().equals(e.backend().backendId()))) {
                return;
            }
            if ("human_participating".equals(backendType)) {
                entries.stream()
                        .filter(e -> "human_participating".equals(e.backendType()))
                        .findFirst()
                        .ifPresent(existing -> {
                            throw new DuplicateParticipatingBackendException(
                                    channelId.toString(), existing.backend().backendId());
                        });
            }
            entries.add(new BackendEntry(backend, backendType, backendNormaliser));
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
     * Fan-out to external backends after MessageService has persisted the message.
     * Called by {@link io.casehub.qhorus.runtime.message.MessageService#dispatch} after persistence.
     * Does NOT call QhorusChannelBackend — persistence already happened.
     *
     * <p>When the delivery pump is enabled ({@code casehub.qhorus.delivery.enabled=true}),
     * backends declaring {@link DeliveryGuarantee#AT_LEAST_ONCE} are skipped — the pump
     * delivers to them after the transaction commits. When the pump is disabled, all backends
     * receive fire-and-forget delivery regardless of their declared guarantee (safe fallback,
     * R3-02).
     *
     * @param channelName the human-readable channel name — must not be null
     * @return {@code true} if at least one backend was skipped for tracked delivery
     */
    public boolean fanOut(UUID channelId, String channelName, OutboundMessage message) {
        ChannelRef ref = new ChannelRef(channelId, Objects.requireNonNull(channelName, "channelName"));
        List<BackendEntry> entries = registry.getOrDefault(channelId, List.of());
        boolean hasTracked = false;
        final boolean deliveryEnabled = deliveryConfig.enabled();
        for (BackendEntry entry : List.copyOf(entries)) {
            if (entry.backend() == agentBackend) continue;
            ChannelBackend backend = entry.backend();
            if (deliveryEnabled && backend.deliveryGuarantee() == DeliveryGuarantee.AT_LEAST_ONCE) {
                hasTracked = true;
                continue;
            }
            Thread.ofVirtual().start(() -> {
                try {
                    backend.post(ref, message);
                } catch (Exception ex) {
                    LOG.errorf(ex, "Backend %s failed on fanOut to channel %s",
                            backend.backendId(), channelId);
                }
            });
        }
        return hasTracked;
    }

    /** Inbound from HumanParticipatingChannelBackend. */
    public void receiveHumanMessage(ChannelRef channel, InboundHumanMessage raw) {
        BackendEntry participatingEntry = registry.getOrDefault(channel.id(), List.of()).stream()
                .filter(e -> "human_participating".equals(e.backendType()))
                .filter(e -> e.normaliser() != null)
                .findFirst()
                .orElse(null);
        InboundNormaliser effective = (participatingEntry != null)
                ? participatingEntry.normaliser()
                : this.normaliser;
        String backendId = (participatingEntry != null)
                ? participatingEntry.backend().backendId()
                : "default";

        NormalisedMessage n = effective.normalise(channel, raw);
        // Uses canonical constructor to bypass builder protocol validation —
        // inbound human messages may carry reply types (DONE/RESPONSE/etc.) with inReplyTo
        // synthesised by the normaliser from human context.
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
                ActorType.HUMAN,
                null,  // deadline
                null,  // telemetry
                null)); // tenancyId — resolved by MessageService from CurrentPrincipal

        // ── Normaliser telemetry EVENT (Refs #202) ────────────────────────────
        // Unconditional: volume bounded by human message rate; EVENTs excluded from check_messages.
        boolean metadataKeyUsed = isValidMessageTypeMetadata(
                raw.metadata() != null ? raw.metadata().get("message-type") : null);
        String telemetryContent = String.format(
                "{\"tool_name\":\"normaliser\",\"backend_id\":\"%s\","
                        + "\"inferred_type\":\"%s\",\"metadata_key_used\":%s,\"in_reply_to_present\":%s}",
                backendId.replace("\\", "\\\\").replace("\"", "\\\""),
                n.type().name(),
                metadataKeyUsed,
                n.inReplyTo() != null);
        messageService.dispatch(MessageDispatch.builder()
                .channelId(channel.id())
                .sender("system:normaliser")
                .type(MessageType.EVENT)
                .telemetry(telemetryContent)
                .actorType(ActorType.SYSTEM)
                .build());
    }

    private static boolean isValidMessageTypeMetadata(String value) {
        if (value == null || value.isBlank()) return false;
        try {
            return MessageType.valueOf(value.toUpperCase()) != MessageType.HANDOFF;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** Inbound from HumanObserverChannelBackend — always EVENT regardless of content. */
    public void receiveObserverSignal(ChannelRef channel, ObserverSignal signal) {
        messageService.dispatch(MessageDispatch.builder()
                .channelId(channel.id())
                .sender("human:" + signal.externalSenderId())
                .type(MessageType.EVENT)
                .actorType(ActorType.HUMAN)
                .build());
    }

    /**
     * Returns a snapshot of registered backends for {@code channelId} that declare
     * {@link DeliveryGuarantee#AT_LEAST_ONCE}. Excludes the internal agent backend.
     * Used by {@code DeliveryService} to drive per-backend delivery tasks.
     *
     * <p>Takes a {@link List#copyOf} snapshot before filtering to prevent
     * {@link java.util.ConcurrentModificationException} from concurrent registration.
     */
    List<BackendEntry> trackedEntries(UUID channelId) {
        List<BackendEntry> entries = registry.getOrDefault(channelId, List.of());
        return List.copyOf(entries).stream()
                .filter(e -> e.backend() != agentBackend)
                .filter(e -> e.backend().deliveryGuarantee() == DeliveryGuarantee.AT_LEAST_ONCE)
                .toList();
    }

    record BackendEntry(ChannelBackend backend, String backendType, InboundNormaliser normaliser) {}

    public record BackendRegistration(String backendId, String backendType, ActorType actorType) {}
}
