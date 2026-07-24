package io.casehub.qhorus.runtime.watchdog;

import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.channel.Channel;
import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.casehub.qhorus.api.instance.Instance;
import io.casehub.qhorus.api.message.Commitment;
import io.casehub.qhorus.api.message.Message;
import io.casehub.qhorus.api.message.MessageDispatch;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.api.store.CrossTenantChannelStore;
import io.casehub.qhorus.api.store.CrossTenantCommitmentStore;
import io.casehub.qhorus.api.store.CrossTenantMessageStore;
import io.casehub.qhorus.api.store.CrossTenantWatchdogStore;
import io.casehub.qhorus.api.store.InstanceStore;
import io.casehub.qhorus.api.store.WatchdogStore;
import io.casehub.qhorus.api.store.query.InstanceQuery;
import io.casehub.qhorus.api.store.query.MessageQuery;
import io.casehub.qhorus.api.watchdog.AgentStaleContext;
import io.casehub.qhorus.api.watchdog.AlertContext;
import io.casehub.qhorus.api.watchdog.ApprovalPendingContext;
import io.casehub.qhorus.api.watchdog.BarrierStuckContext;
import io.casehub.qhorus.api.watchdog.ChannelIdleContext;
import io.casehub.qhorus.api.watchdog.CircularDelegationContext;
import io.casehub.qhorus.api.watchdog.ContextPressureContext;
import io.casehub.qhorus.api.watchdog.ConversationStallContext;
import io.casehub.qhorus.api.watchdog.EchoChamberContext;
import io.casehub.qhorus.api.watchdog.LoopDetectedContext;
import io.casehub.qhorus.api.watchdog.ObligationFanOutContext;
import io.casehub.qhorus.api.watchdog.QueueDepthContext;
import io.casehub.qhorus.api.watchdog.Watchdog;
import io.casehub.qhorus.api.watchdog.WatchdogAlertEvent;
import io.casehub.qhorus.runtime.config.QhorusConfig;
import io.casehub.qhorus.runtime.ledger.MessageLedgerEntryRepository;
import io.casehub.qhorus.runtime.message.MessageService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Evaluates all registered watchdog conditions and fires alert messages
 * to their notification channels when conditions are met.
 *
 * <p>
 * Called by the Quarkus Scheduler at a configurable interval, and directly
 * by tests via injection for deterministic testing without scheduler timing.
 *
 * <p>
 * Debounce rule: a watchdog does not re-fire if {@code lastFiredAt} is within
 * {@code thresholdSeconds} of now (or within 1 second for threshold=0 conditions).
 */
@ApplicationScoped
public class WatchdogEvaluationService {

    private static final List<MessageType> RESOLUTION_TYPES = List.of(
            MessageType.DONE, MessageType.FAILURE, MessageType.DECLINE, MessageType.HANDOFF);
    @Inject
    QhorusConfig config;
    @Inject
    MessageService messageService;
    @Inject
    WatchdogStore watchdogStore;
    @Inject
    CrossTenantChannelStore crossTenantChannelStore;
    @Inject
    CrossTenantMessageStore crossTenantMessageStore;
    @Inject
    CrossTenantCommitmentStore crossTenantCommitmentStore;
    @Inject
    CrossTenantWatchdogStore crossTenantWatchdogStore;
    @Inject
    InstanceStore instanceStore;
    @Inject
    Event<WatchdogAlertEvent>    alertEvents;
    @Inject
    MessageLedgerEntryRepository messageRepo;
    @Inject
    io.casehub.qhorus.api.store.ChannelMembershipStore channelMembershipStore;


    /**
     * Evaluate all registered watchdogs and fire alerts for met conditions.
     */
    @Transactional
    public void evaluateAll() {
        if (!config.watchdog().enabled()) {
            return;
        }

        List<Watchdog> watchdogs = crossTenantWatchdogStore.listAll().stream()
                                                           .filter(Objects::nonNull)
                                                           .toList();
        Instant now = Instant.now();

        for (Watchdog w : watchdogs) {
            if (isDebounced(w, now)) {
                continue;
            }
            boolean fired = switch (w.conditionType()) {
                case BARRIER_STUCK -> evaluateBarrierStuck(w, now);
                case APPROVAL_PENDING -> evaluateApprovalPending(w, now);
                case AGENT_STALE -> evaluateAgentStale(w, now);
                case CHANNEL_IDLE -> evaluateChannelIdle(w, now);
                case QUEUE_DEPTH -> evaluateQueueDepth(w, now);
                case CONTEXT_PRESSURE -> evaluateContextPressure(w, now);
                case LOOP_DETECTED -> evaluateLoopDetected(w, now);
                case OBLIGATION_FAN_OUT -> evaluateObligationFanOut(w, now);
                case CONVERSATION_STALL -> evaluateConversationStall(w, now);
                case ECHO_CHAMBER -> evaluateEchoChamber(w, now);
                case CIRCULAR_DELEGATION -> evaluateCircularDelegation(w, now);
            };
            if (fired) {
                Watchdog updated = w.toBuilder().lastFiredAt(now).build();
                watchdogStore.put(updated);
            }
        }
    }

    /**
     * Debounce: skip if lastFiredAt is recent relative to threshold.
     * For threshold=0 conditions, use a 1-second window to prevent double-fire
     * within the same evaluation cycle.
     */
    private boolean isDebounced(Watchdog w, Instant now) {
        if (w.lastFiredAt() == null) {
            return false;
        }
        long windowSeconds = w.thresholdSeconds() != null && w.thresholdSeconds() > 0
                             ? w.thresholdSeconds()
                             : 1L;
        return w.lastFiredAt().isAfter(now.minusSeconds(windowSeconds));
    }

    private boolean evaluateBarrierStuck(Watchdog w, Instant now) {
        int     threshold = w.thresholdSeconds() != null ? w.thresholdSeconds() : 300;
        Instant cutoff    = now.minusSeconds(threshold);

        List<Channel> barriers = crossTenantChannelStore.listAll().stream()
                                                        .filter(ch -> ch.semantic() == ChannelSemantic.BARRIER)
                                                        .filter(ch -> "*".equals(w.targetName()) || ch.name().equals(w.targetName()))
                                                        .filter(ch -> ch.lastActivityAt() == null || ch.lastActivityAt().isBefore(cutoff) || threshold == 0)
                                                        .toList();

        boolean fired = false;
        for (Channel ch : barriers) {
            List<String> required = ch.barrierContributors() != null
                                    ? ch.barrierContributors()
                                    : List.of();
            if (required.isEmpty()) {continue;}

            List<String> written = crossTenantMessageStore.distinctSendersByChannel(ch.id(), MessageType.EVENT);
            List<String> missing = required.stream()
                                           .map(String::trim)
                                           .filter(r -> !r.isBlank())
                                           .filter(r -> !written.contains(r))
                                           .toList();

            if (!missing.isEmpty()) {
                Instant effectiveActivity = ch.lastActivityAt() != null ? ch.lastActivityAt() : ch.createdAt();
                long    elapsedSeconds    = now.getEpochSecond() - effectiveActivity.getEpochSecond();

                List<String> notDelivered        = new java.util.ArrayList<>();
                List<String> deliveredNoResponse = new java.util.ArrayList<>();
                if (io.casehub.qhorus.runtime.channel.ChannelService.isDeliveryTrackingEnabled(ch)) {
                    Long latestId = crossTenantMessageStore.findLastMessage(ch.id()).map(Message::id).orElse(null);
                    for (String contributor : missing) {
                        var membership = channelMembershipStore.find(ch.id(), contributor);
                        if (membership.isPresent() && membership.get().lastDeliveredMessageId() != null
                            && latestId != null && membership.get().lastDeliveredMessageId() >= latestId) {
                            deliveredNoResponse.add(contributor);
                        } else {
                            notDelivered.add(contributor);
                        }
                    }
                } else {
                    notDelivered.addAll(missing);
                }

                String summary = "BARRIER_STUCK: channel='" + ch.name() + "' waiting for contributors";
                fireAlert(w, summary,
                          new BarrierStuckContext(ch.id(), ch.name(), missing, notDelivered, deliveredNoResponse, elapsedSeconds), now);
                fired = true;
            }
        }
        return fired;}

    private boolean evaluateApprovalPending(Watchdog w, Instant now) {
        int threshold = w.thresholdSeconds() != null ? w.thresholdSeconds() : 300;

        // Threshold formula preserved verbatim from original: for threshold=300, fires for
        // commitments expired >240s ago; for threshold=60, expiring right now; for
        // threshold=0, all commitments with any expiry. See design spec 2026-05-28.
        List<Commitment> pending = crossTenantCommitmentStore.findAllOpen()
                                                             .stream()
                                                             .filter(c -> c.expiresAt() != null)
                                                             .filter(c -> threshold == 0 || c.expiresAt().isBefore(now.plusSeconds(60 - threshold)))
                                                             .toList();

        if (!pending.isEmpty()) {
            Instant oldestExpiry = pending.stream()
                                          .map(Commitment::expiresAt)
                                          .min(Comparator.naturalOrder())
                                          .orElse(null);
            String summary = "APPROVAL_PENDING: " + pending.size() + " approval(s) awaiting human response";
            fireAlert(w, summary, new ApprovalPendingContext(pending.size(), oldestExpiry), now);
            return true;
        }
        return false;
    }

    private boolean evaluateAgentStale(Watchdog w, Instant now) {
        int     threshold = w.thresholdSeconds() != null ? w.thresholdSeconds() : 300;
        Instant cutoff    = now.minusSeconds(threshold);

        List<Instance> staleInstances = instanceStore.scan(
                InstanceQuery.builder().status("stale").staleOlderThan(cutoff).build());

        if (!staleInstances.isEmpty()) {
            List<String> ids = staleInstances.stream()
                                             .limit(10)
                                             .map(i -> i.id().toString())
                                             .toList();
            String summary = "AGENT_STALE: " + staleInstances.size() + " stale agent(s) detected";
            fireAlert(w, summary, new AgentStaleContext(staleInstances.size(), ids), now);
            return true;
        }
        return false;
    }

    private boolean evaluateChannelIdle(Watchdog w, Instant now) {
        int     threshold = w.thresholdSeconds() != null ? w.thresholdSeconds() : 600;
        Instant cutoff    = now.minusSeconds(threshold);

        List<Channel> idle = crossTenantChannelStore.listAll().stream()
                                                    .filter(ch -> "*".equals(w.targetName()) || ch.name().equals(w.targetName()))
                                                    .filter(ch -> threshold == 0 || ch.lastActivityAt() == null || ch.lastActivityAt().isBefore(cutoff))
                                                    .toList();

        if (!idle.isEmpty()) {
            List<String> names   = idle.stream().map(Channel::name).limit(3).toList();
            String       joined  = String.join(", ", names);
            String       summary = "CHANNEL_IDLE: channel(s) idle > " + threshold + "s: " + joined;
            fireAlert(w, summary, new ChannelIdleContext(names, threshold), now);
            return true;
        }
        return false;
    }

    private boolean evaluateQueueDepth(Watchdog w, Instant now) {
        int threshold = w.thresholdCount() != null ? w.thresholdCount() : 100;

        List<Channel> channels = crossTenantChannelStore.listAll().stream()
                                                        .filter(ch -> "*".equals(w.targetName()) || ch.name().equals(w.targetName()))
                                                        .toList();

        // Fires on the FIRST channel that exceeds the threshold. If multiple channels
        // are over-depth, only one alert fires per evaluation cycle — pre-existing behaviour.
        for (Channel ch : channels) {
            long count = crossTenantMessageStore.count(
                    MessageQuery.builder()
                                .channelId(ch.id())
                                .excludeTypes(List.of(MessageType.EVENT))
                                .build());
            if (count >= threshold) {
                String summary = "QUEUE_DEPTH: channel='" + ch.name() + "' has " + count
                                 + " messages (threshold=" + threshold + ")";
                fireAlert(w, summary, new QueueDepthContext(ch.name(), count, threshold), now);
                return true;
            }
        }
        return false;
    }

    private void fireAlert(Watchdog w, String summary, AlertContext context, Instant now) {
        // 1. Fire async event FIRST — external delivery is independent of internal channel
        //    success. fireAsync() dispatches immediately; it does not wait for the outer
        //    @Transactional boundary to commit.
        //    Ghost-notification risk (tx rollback after fire): narrow window, accepted.
        //    Crash/missed-alert risk (app crashes before observer delivers): accepted — CDI
        //    async is at-most-once; outbox pattern required for at-least-once.
        alertEvents.fireAsync(new WatchdogAlertEvent(
                w.id(), w.targetName(), w.notificationChannel(), summary, now, context));

        // 2. Internal channel dispatch SECOND — failure does not suppress the event above.
        //    Use cross-tenant lookup scoped to the watchdog's tenancy — no CDI request context
        //    available in the scheduler thread. Pass w.tenancyId() explicitly so MessageService
        //    can route without CurrentPrincipal (GE-20260531-446fea pattern).
        Optional<Channel> notifChannel = crossTenantChannelStore
                                                 .findByNameAndTenancy(w.notificationChannel(), w.tenancyId());
        if (notifChannel.isEmpty()) {
            return;
        }
        messageService.dispatch(MessageDispatch.builder()
                                               .channelId(notifChannel.get().id())
                                               .sender("system:watchdog")
                                               .type(MessageType.STATUS)
                                               .content(summary)
                                               .actorType(ActorType.SYSTEM)
                                               .tenancyId(w.tenancyId())
                                               .build());
    }

    private boolean evaluateContextPressure(Watchdog w, Instant now) {
        int threshold = w.thresholdCount() != null ? w.thresholdCount() : 80;

        List<Channel> channels = crossTenantChannelStore.listAll().stream()
                                                        .filter(ch -> "*".equals(w.targetName()) || ch.name().equals(w.targetName()))
                                                        .toList();

        boolean fired = false;
        for (Channel ch : channels) {
            var entries = messageRepo.findLatestContextPressure(ch.id(), w.tenancyId());
            for (var entry : entries) {
                if (entry.contextWindowPct != null && entry.contextWindowPct >= threshold) {
                    String summary = "CONTEXT_PRESSURE: agent='" + entry.actorId
                                     + "' at " + entry.contextWindowPct + "% on channel='" + ch.name() + "'";
                    fireAlert(w, summary,
                              new ContextPressureContext(ch.id(), ch.name(),
                                                         entry.actorId, entry.contextWindowPct),
                              now);
                    fired = true;
                }
            }
        }
        return fired;
    }

    private boolean evaluateLoopDetected(Watchdog w, Instant now) {
        int     repetitionCount     = w.thresholdCount() != null ? w.thresholdCount() : 5;
        int     windowSeconds       = w.thresholdSeconds() != null ? w.thresholdSeconds() : 300;
        double  similarityThreshold = w.similarityPct() != null ? w.similarityPct() / 100.0 : 0.70;
        Instant cutoff              = now.minusSeconds(windowSeconds);

        List<Channel> channels = crossTenantChannelStore.listAll().stream()
                                                        .filter(ch -> "*".equals(w.targetName()) || ch.name().equals(w.targetName()))
                                                        .toList();

        boolean fired = false;
        for (Channel ch : channels) {
            List<Message> recent = crossTenantMessageStore.scan(
                    MessageQuery.builder().channelId(ch.id())
                                .excludeTypes(List.of(MessageType.EVENT))
                                .limit(repetitionCount * 3).descending(true).build());

            Map<String, List<Message>> bySender = recent.stream()
                                                        .filter(m -> m.createdAt() != null && m.createdAt().isAfter(cutoff))
                                                        .collect(Collectors.groupingBy(Message::sender));

            for (var entry : bySender.entrySet()) {
                List<Message> msgs = entry.getValue();
                if (msgs.size() < repetitionCount) {
                    continue;
                }
                msgs = new ArrayList<>(msgs);
                msgs.sort(Comparator.comparing(Message::createdAt));

                int    longestRun = 0;
                int    currentRun = 0;
                double maxSim     = 0.0;
                for (int i = 1; i < msgs.size(); i++) {
                    double sim = JaccardSimilarity.similarity(msgs.get(i - 1).content(), msgs.get(i).content());
                    if (sim >= similarityThreshold) {
                        currentRun++;
                        maxSim = Math.max(maxSim, sim);
                    } else {
                        longestRun = Math.max(longestRun, currentRun);
                        currentRun = 0;
                    }
                }
                longestRun = Math.max(longestRun, currentRun);

                if (longestRun >= repetitionCount - 1) {
                    String summary = "LOOP_DETECTED: sender='" + entry.getKey()
                                     + "' repeated " + (longestRun + 1) + " similar messages on '" + ch.name() + "'";
                    fireAlert(w, summary,
                              new LoopDetectedContext(ch.id(), ch.name(), entry.getKey(),
                                                      longestRun + 1, maxSim), now);
                    fired = true;
                }
            }
        }
        return fired;
    }

    private boolean evaluateObligationFanOut(Watchdog w, Instant now) {
        int     deadlineSeconds = w.thresholdSeconds() != null ? w.thresholdSeconds() : 300;
        Instant cutoff          = now.minusSeconds(deadlineSeconds);

        List<Channel> channels = crossTenantChannelStore.listAll().stream()
                                                        .filter(ch -> "*".equals(w.targetName()) || ch.name().equals(w.targetName()))
                                                        .toList();

        boolean fired = false;
        for (Channel ch : channels) {
            List<Commitment> stale = crossTenantCommitmentStore.findOpenByChannel(ch.id()).stream()
                                                               .filter(c -> c.messageType() == MessageType.COMMAND)
                                                               .filter(c -> c.acknowledgedAt() == null)
                                                               .filter(c -> c.createdAt() != null && c.createdAt().isBefore(cutoff))
                                                               .filter(c -> {
                                                                   long responseCount = crossTenantMessageStore.count(
                                                                           MessageQuery.builder().channelId(ch.id())
                                                                                       .correlationId(c.correlationId())
                                                                                       .excludeTypes(List.of(MessageType.COMMAND, MessageType.EVENT))
                                                                                       .build());
                                                                   return responseCount == 0;
                                                               })
                                                               .toList();

            if (!stale.isEmpty()) {
                List<String> corrIds = stale.stream()
                                            .map(Commitment::correlationId).limit(5).toList();
                String summary = "OBLIGATION_FAN_OUT: " + stale.size()
                                 + " unresponded obligation(s) on '" + ch.name() + "'";
                fireAlert(w, summary,
                          new ObligationFanOutContext(ch.id(), ch.name(), stale.size(), corrIds), now);
                fired = true;
            }
        }
        return fired;
    }

    private boolean evaluateConversationStall(Watchdog w, Instant now) {
        int     stallSeconds = w.thresholdSeconds() != null ? w.thresholdSeconds() : 600;
        Instant cutoff       = now.minusSeconds(stallSeconds);

        List<Channel> channels = crossTenantChannelStore.listAll().stream()
                                                        .filter(ch -> "*".equals(w.targetName()) || ch.name().equals(w.targetName()))
                                                        .toList();

        boolean fired = false;
        for (Channel ch : channels) {
            List<Commitment> active = crossTenantCommitmentStore.findOpenByChannel(ch.id());
            if (active.isEmpty()) {
                continue;
            }

            List<Commitment> aged = active.stream()
                                          .filter(c -> c.createdAt() != null && c.createdAt().isBefore(cutoff))
                                          .toList();
            if (aged.isEmpty()) {
                continue;
            }

            List<Commitment> stalled = aged.stream()
                                           .filter(c -> {
                                               List<Message> resolutions = crossTenantMessageStore.scan(
                                                                                                          MessageQuery.builder().channelId(ch.id())
                                                                                                                      .correlationId(c.correlationId())
                                                                                                                      .limit(100).descending(true).build()).stream()
                                                                                                  .filter(m -> RESOLUTION_TYPES.contains(m.messageType()))
                                                                                                  .toList();
                                               if (resolutions.isEmpty()) {
                                                   return true;
                                               }
                                               Message latest = resolutions.get(0);
                                               return latest.createdAt() != null && latest.createdAt().isBefore(cutoff);
                                           })
                                           .toList();

            if (!stalled.isEmpty()) {
                long maxStallSeconds = stalled.stream()
                                              .mapToLong(c -> now.getEpochSecond() - c.createdAt().getEpochSecond())
                                              .max().orElse(0L);
                List<String> corrIds = stalled.stream()
                                              .map(Commitment::correlationId).limit(5).toList();

                Boolean deliveryConfirmed = null;
                if (io.casehub.qhorus.runtime.channel.ChannelService.isDeliveryTrackingEnabled(ch) && !stalled.isEmpty()) {
                    Commitment firstStalled = stalled.get(0);
                    String     obligor      = firstStalled.obligor();
                    if (obligor != null) {
                        var membership = channelMembershipStore.find(ch.id(), obligor);
                        if (membership.isPresent()) {
                            Long delivered    = membership.get().lastDeliveredMessageId();
                            var commandMsg = crossTenantMessageStore.scan(
                                    MessageQuery.builder().channelId(ch.id())
                                            .correlationId(firstStalled.correlationId())
                                            .limit(1).build()).stream().findFirst();
                            Long commandMsgId = commandMsg.map(Message::id).orElse(null);
                            if (delivered != null && commandMsgId != null) {
                                deliveryConfirmed = delivered >= commandMsgId;
                            } else {
                                deliveryConfirmed = false;
                            }
                        }
                    }
                }

                String summary = "CONVERSATION_STALL: " + stalled.size()
                                 + " stalled correlation(s) on '" + ch.name() + "'";
                fireAlert(w, summary,
                          new ConversationStallContext(ch.id(), ch.name(), stalled.size(),
                                                       corrIds, maxStallSeconds, deliveryConfirmed), now);
                fired = true;
            }
        }
        return fired;}

    private boolean evaluateEchoChamber(Watchdog w, Instant now) {
        int     windowSeconds       = w.thresholdSeconds() != null ? w.thresholdSeconds() : 300;
        int     minAgents           = w.thresholdCount() != null ? w.thresholdCount() : 2;
        double  similarityThreshold = w.similarityPct() != null ? w.similarityPct() / 100.0 : 0.70;
        Instant cutoff              = now.minusSeconds(windowSeconds);

        List<Channel> channels = crossTenantChannelStore.listAll().stream()
                                                        .filter(ch -> "*".equals(w.targetName()) || ch.name().equals(w.targetName()))
                                                        .toList();

        boolean fired = false;
        for (Channel ch : channels) {
            List<Message> recent = crossTenantMessageStore.scan(
                                                                  MessageQuery.builder().channelId(ch.id())
                                                                              .excludeTypes(List.of(MessageType.EVENT))
                                                                              .limit(50).descending(true).build()).stream()
                                                          .filter(m -> m.createdAt() != null && m.createdAt().isAfter(cutoff))
                                                          .toList();

            Map<String, List<Message>> bySender = recent.stream()
                                                        .collect(Collectors.groupingBy(Message::sender));

            if (bySender.size() < minAgents) {
                continue;
            }

            int          similarPairs = 0;
            double       maxSim       = 0.0;
            Set<String>  participants = new HashSet<>();
            List<String> senders      = new ArrayList<>(bySender.keySet());
            for (int i = 0; i < senders.size(); i++) {
                for (int j = i + 1; j < senders.size(); j++) {
                    for (Message ma : bySender.get(senders.get(i))) {
                        for (Message mb : bySender.get(senders.get(j))) {
                            double sim = JaccardSimilarity.similarity(ma.content(), mb.content());
                            if (sim >= similarityThreshold) {
                                similarPairs++;
                                maxSim = Math.max(maxSim, sim);
                                participants.add(senders.get(i));
                                participants.add(senders.get(j));
                            }
                        }
                    }
                }
            }

            if (similarPairs >= 2) {
                String summary = "ECHO_CHAMBER: " + similarPairs
                                 + " echoed message pair(s) on '" + ch.name() + "'";
                fireAlert(w, summary,
                          new EchoChamberContext(ch.id(), ch.name(),
                                                 List.copyOf(participants), maxSim), now);
                fired = true;
            }
        }
        return fired;
    }

    private boolean evaluateCircularDelegation(Watchdog w, Instant now) {
        int maxDepth = w.thresholdCount() != null ? w.thresholdCount() : 10;

        List<Channel> channels = crossTenantChannelStore.listAll().stream()
                                                        .filter(ch -> "*".equals(w.targetName()) || ch.name().equals(w.targetName()))
                                                        .toList();

        boolean fired = false;
        for (Channel ch : channels) {
            List<Commitment> open = crossTenantCommitmentStore.findOpenByChannel(ch.id()).stream()
                                                              .filter(c -> c.parentCommitmentId() != null)
                                                              .toList();

            Set<String> checked = new HashSet<>();
            for (Commitment c : open) {
                if (!checked.add(c.correlationId())) {continue;}

                List<Commitment> chain = crossTenantCommitmentStore.findAllByCorrelationId(c.correlationId());
                if (chain.size() > maxDepth) {continue;}

                java.util.LinkedHashSet<String> seen  = new java.util.LinkedHashSet<>();
                List<String>                    cycle = null;
                for (Commitment link : chain) {
                    if (link.obligor() == null) {continue;}
                    if (!seen.add(link.obligor())) {
                        List<String> ordered = new ArrayList<>(seen);
                        int          start   = ordered.indexOf(link.obligor());
                        cycle = new ArrayList<>(ordered.subList(start, ordered.size()));
                        cycle.add(link.obligor());
                        break;
                    }
                }

                if (cycle != null) {
                    String summary = "CIRCULAR_DELEGATION: cycle detected on '"
                                     + ch.name() + "' - " + String.join(" -> ", cycle);
                    fireAlert(w, summary,
                              new CircularDelegationContext(ch.id(), ch.name(),
                                                            c.correlationId(), List.copyOf(cycle), chain.size()), now);
                    fired = true;
                }
            }
        }
        return fired;
    }


}
