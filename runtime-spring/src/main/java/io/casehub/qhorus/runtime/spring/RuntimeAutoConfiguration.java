package io.casehub.qhorus.runtime.spring;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.platform.api.capacity.ActorCapacityView;
import io.casehub.platform.api.capacity.RedistributionPolicy;
import io.casehub.platform.api.identity.CurrentPrincipal;
import io.casehub.qhorus.api.gateway.AgentChannelBackend;
import io.casehub.qhorus.api.gateway.ChannelActivityBroadcaster;
import io.casehub.qhorus.api.gateway.InboundNormaliser;
import io.casehub.qhorus.api.spi.ChannelProtocol;
import io.casehub.qhorus.api.spi.ObligorTrustPolicy;
import io.casehub.qhorus.api.spi.RenderableProjection;
import io.casehub.qhorus.api.spi.SummaryUpdateHook;
import io.casehub.qhorus.api.store.ChannelBindingStore;
import io.casehub.qhorus.api.store.ChannelMembershipStore;
import io.casehub.qhorus.api.store.ChannelStore;
import io.casehub.qhorus.api.store.ChannelSummaryStore;
import io.casehub.qhorus.api.store.CommitmentStore;
import io.casehub.qhorus.api.store.CrossTenantChannelStore;
import io.casehub.qhorus.api.store.CrossTenantChannelSummaryStore;
import io.casehub.qhorus.api.store.CrossTenantCommitmentStore;
import io.casehub.qhorus.api.store.CrossTenantMessageStore;
import io.casehub.qhorus.api.store.CrossTenantWatchdogStore;
import io.casehub.qhorus.api.store.DataStore;
import io.casehub.qhorus.api.store.DeliveryCursorStore;
import io.casehub.qhorus.api.store.InstanceStore;
import io.casehub.qhorus.api.store.MessageStore;
import io.casehub.qhorus.api.store.ReactionStore;
import io.casehub.qhorus.api.store.SpaceStore;
import io.casehub.qhorus.api.store.TopicStore;
import io.casehub.qhorus.api.store.WatchdogStore;
import io.casehub.qhorus.api.watchdog.AlertDeliveryTarget;
import io.casehub.qhorus.runtime.audit.EvidentialChecker;
import io.casehub.qhorus.runtime.capacity.QhorusRedistributionExecutor;
import io.casehub.qhorus.runtime.capacity.RedistributionDelegate;
import io.casehub.qhorus.runtime.channel.ChannelCreateHelper;
import io.casehub.qhorus.runtime.channel.ChannelMembershipService;
import io.casehub.qhorus.runtime.channel.ChannelService;
import io.casehub.qhorus.runtime.channel.ChannelSummaryScheduler;
import io.casehub.qhorus.runtime.channel.ChannelSummaryService;
import io.casehub.qhorus.runtime.channel.NoOpSummaryUpdateHook;
import io.casehub.qhorus.runtime.channel.PresenceService;
import io.casehub.qhorus.runtime.channel.RateLimiter;
import io.casehub.qhorus.runtime.channel.SpaceService;
import io.casehub.qhorus.runtime.config.DeliveryConfig;
import io.casehub.qhorus.runtime.config.PresenceConfig;
import io.casehub.qhorus.runtime.config.QhorusConfig;
import io.casehub.qhorus.runtime.config.QhorusTracingConfig;
import io.casehub.qhorus.runtime.data.DataService;
import io.casehub.qhorus.runtime.gateway.ChannelGateway;
import io.casehub.qhorus.runtime.gateway.DeliveryBatchExecutor;
import io.casehub.qhorus.runtime.gateway.DeliveryService;
import io.casehub.qhorus.runtime.gateway.DeliverySignalQueue;
import io.casehub.qhorus.runtime.gateway.InProcessMessageBus;
import io.casehub.qhorus.runtime.gateway.NoOpChannelActivityBroadcaster;
import io.casehub.qhorus.runtime.gateway.QhorusChannelBackend;
import io.casehub.qhorus.runtime.gateway.QhorusCloudEventAdapter;
import io.casehub.qhorus.runtime.instance.InstanceService;
import io.casehub.qhorus.runtime.ledger.AgreementCredibilityPolicy;
import io.casehub.qhorus.runtime.ledger.DefaultInstanceActorIdProvider;
import io.casehub.qhorus.runtime.ledger.ReviewerResolver;
import io.casehub.qhorus.runtime.ledger.StoredCommitmentAttestationPolicy;
import io.casehub.qhorus.runtime.message.CommitmentService;
import io.casehub.qhorus.runtime.message.CorrelationIntegrityChecker;
import io.casehub.qhorus.runtime.message.DefaultObligorTrustPolicy;
import io.casehub.qhorus.runtime.message.EnforcementExecutor;
import io.casehub.qhorus.runtime.message.MessageService;
import io.casehub.qhorus.runtime.message.MessageTypePolicy;
import io.casehub.qhorus.runtime.message.ProjectionRegistry;
import io.casehub.qhorus.runtime.message.ReactionService;
import io.casehub.qhorus.runtime.message.RoutingBridge;
import io.casehub.qhorus.runtime.message.StoredMessageTypePolicy;
import io.casehub.qhorus.runtime.message.TopicService;
import io.casehub.qhorus.runtime.message.protocol.ContributionRequiredProtocol;
import io.casehub.qhorus.runtime.message.protocol.ProtocolRegistry;
import io.casehub.qhorus.runtime.message.protocol.RequestResponseProtocol;
import io.casehub.qhorus.runtime.message.protocol.RoundRobinProtocol;
import io.casehub.qhorus.runtime.message.protocol.TaskCompletionProtocol;
import io.casehub.qhorus.runtime.spring.config.DeliveryConfigProperties;
import io.casehub.qhorus.runtime.spring.config.PresenceConfigProperties;
import io.casehub.qhorus.runtime.spring.config.QhorusConfigProperties;
import io.casehub.qhorus.runtime.spring.config.QhorusTracingConfigProperties;
import io.casehub.qhorus.runtime.watchdog.ConfiguredWatchdogAlertRouter;
import io.casehub.qhorus.runtime.watchdog.WatchdogEvaluationService;
import io.opentelemetry.api.trace.Tracer;
import jakarta.transaction.TransactionSynchronizationRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

@AutoConfiguration
@ConditionalOnClass(MessageService.class)
@EnableConfigurationProperties({
        QhorusConfigProperties.class,
        DeliveryConfigProperties.class,
        PresenceConfigProperties.class,
        QhorusTracingConfigProperties.class
})
@EnableScheduling
public class RuntimeAutoConfiguration {

    // ── Config ────────────────────────────────────────────────────────────

    @Bean
    @ConditionalOnMissingBean
    public QhorusConfig qhorusConfig(QhorusConfigProperties props) { return props; }

    @Bean
    @ConditionalOnMissingBean
    public DeliveryConfig deliveryConfig(DeliveryConfigProperties props) { return props; }

    @Bean
    @ConditionalOnMissingBean
    public PresenceConfig presenceConfig(PresenceConfigProperties props) { return props; }

    @Bean
    @ConditionalOnMissingBean
    public QhorusTracingConfig qhorusTracingConfig(QhorusTracingConfigProperties props) { return props; }

    @Bean
    @ConditionalOnMissingBean
    public TransactionSynchronizationRegistry transactionSynchronizationRegistry() {
        return new SpringTransactionSynchronizationRegistry();
    }

    @Bean
    @ConditionalOnMissingBean
    public Clock clock() { return Clock.systemUTC(); }

    // ── Default implementations (overridable) ─────────────────────────────

    @Bean
    @ConditionalOnMissingBean
    public SummaryUpdateHook summaryUpdateHook() { return new NoOpSummaryUpdateHook(); }

    @Bean
    @ConditionalOnMissingBean
    public io.casehub.qhorus.api.spi.InstanceActorIdProvider instanceActorIdProvider() {
        return new DefaultInstanceActorIdProvider();
    }

    @Bean
    @ConditionalOnMissingBean
    public ChannelActivityBroadcaster channelActivityBroadcaster() {
        return new NoOpChannelActivityBroadcaster();
    }

    @Bean
    @ConditionalOnMissingBean
    public AgentChannelBackend agentChannelBackend() { return new QhorusChannelBackend(); }

    @Bean
    @ConditionalOnMissingBean
    public MessageTypePolicy messageTypePolicy() { return new StoredMessageTypePolicy(); }

    @Bean
    @ConditionalOnMissingBean
    public ObligorTrustPolicy obligorTrustPolicy(
            QhorusConfig config,
            Optional<io.casehub.ledger.core.trust.TrustGateService> trustGateService) {
        return new DefaultObligorTrustPolicy(config.commitment().minObligorTrust(),
                trustGateService.orElse(null));
    }

    @Bean
    @ConditionalOnMissingBean
    public io.casehub.qhorus.api.spi.CommitmentAttestationPolicy commitmentAttestationPolicy(
            QhorusConfig config, EvidentialChecker evidentialChecker) {
        return new StoredCommitmentAttestationPolicy(
                config.attestation().doneConfidence(), config.attestation().failureConfidence(),
                config.attestation().declineConfidence(), config.attestation().responseConfidence(),
                evidentialChecker);
    }

    @Bean
    @ConditionalOnMissingBean
    public AgreementCredibilityPolicy agreementCredibilityPolicy(
            io.casehub.ledger.api.spi.LedgerEntryRepository ledger, QhorusConfig config) {
        return new AgreementCredibilityPolicy(ledger,
                config.attestation().credibilityMinDataPoints(),
                config.attestation().credibilityLowAgreementThreshold());
    }

    @Bean
    @ConditionalOnMissingBean
    public io.casehub.qhorus.api.watchdog.WatchdogAlertRouter watchdogAlertRouter(QhorusConfig config) {
        List<AlertDeliveryTarget> list = config.watchdog().alert().endpoints().stream()
                .map(ep -> new AlertDeliveryTarget(ep.connectorId(), ep.destination()))
                .toList();
        return new ConfiguredWatchdogAlertRouter(list);
    }

    // ── Channel services ──────────────────────────────────────────────────

    @Bean
    public ChannelService channelService(CurrentPrincipal currentPrincipal, ChannelStore channelStore,
                                         MessageStore messageStore, ChannelMembershipStore membershipStore,
                                         ChannelBindingStore channelBindingStore, ChannelGateway channelGateway,
                                         ChannelCreateHelper channelCreateHelper) {
        return new ChannelService(currentPrincipal, channelStore, messageStore, membershipStore,
                channelBindingStore, channelGateway, channelCreateHelper);
    }

    @Bean
    public ChannelCreateHelper channelCreateHelper(ChannelStore channelStore, ChannelBindingStore channelBindingStore,
                                                    ChannelGateway channelGateway, CurrentPrincipal currentPrincipal) {
        return new ChannelCreateHelper(channelStore, channelBindingStore, channelGateway, currentPrincipal);
    }

    @Bean
    public ChannelSummaryService channelSummaryService(ChannelSummaryStore summaryStore, ChannelService channelService,
                                                        CrossTenantChannelStore crossTenantChannelStore,
                                                        MessageStore messageStore, SummaryUpdateHook hook,
                                                        ApplicationEventPublisher publisher) {
        return new ChannelSummaryService(summaryStore, channelService, crossTenantChannelStore,
                messageStore, hook, event -> publisher.publishEvent(event));
    }

    @Bean
    public ChannelSummaryScheduler channelSummaryScheduler(QhorusConfig config,
                                                            CrossTenantChannelSummaryStore crossTenantSummaryStore,
                                                            ChannelSummaryStore summaryStore,
                                                            CrossTenantChannelStore crossTenantChannelStore,
                                                            CrossTenantMessageStore crossTenantMessageStore,
                                                            SummaryUpdateHook hook,
                                                            ApplicationEventPublisher publisher) {
        return new ChannelSummaryScheduler(config, crossTenantSummaryStore, summaryStore,
                crossTenantChannelStore, crossTenantMessageStore, hook, event -> publisher.publishEvent(event));
    }

    @Bean
    public PresenceService presenceService(PresenceConfig config, Clock clock,
                                           ChannelMembershipService membershipService,
                                           CurrentPrincipal currentPrincipal,
                                           ApplicationEventPublisher publisher) {
        return new PresenceService(config, clock, membershipService, currentPrincipal,
                event -> publisher.publishEvent(event));
    }

    @Bean
    public ChannelMembershipService channelMembershipService(ChannelMembershipStore membershipStore,
                                                             MessageStore messageStore,
                                                             CurrentPrincipal currentPrincipal) {
        return new ChannelMembershipService(membershipStore, messageStore, currentPrincipal);
    }

    @Bean
    public SpaceService spaceService(SpaceStore spaceStore, ChannelStore channelStore,
                                     CurrentPrincipal currentPrincipal, ApplicationEventPublisher publisher) {
        return new SpaceService(spaceStore, channelStore, currentPrincipal,
                event -> publisher.publishEvent(event));
    }

    // ── Gateway ───────────────────────────────────────────────────────────

    @Bean
    @ConditionalOnMissingBean
    public InProcessMessageBus inProcessMessageBus(ApplicationEventPublisher publisher) {
        return new InProcessMessageBus(event -> publisher.publishEvent(event));
    }

    @Bean
    public QhorusCloudEventAdapter qhorusCloudEventAdapter(ApplicationEventPublisher publisher,
                                                            ObjectMapper objectMapper) {
        return new QhorusCloudEventAdapter(event -> publisher.publishEvent(event), objectMapper);
    }

    @Bean
    public ChannelGateway channelGateway(AgentChannelBackend agentBackend, InboundNormaliser normaliser,
                                         MessageService messageService, ChannelService channelService,
                                         CrossTenantChannelStore crossTenantChannelStore,
                                         ApplicationEventPublisher publisher, DeliveryConfig deliveryConfig,
                                         CrossTenantMessageStore crossTenantMessageStore,
                                         ChannelMembershipService membershipService,
                                         Optional<Tracer> tracer, QhorusTracingConfig tracingConfig) {
        Supplier<Tracer> tracerSupplier = tracer.map(t -> (Supplier<Tracer>) () -> t).orElse(null);
        ChannelGateway gw = new ChannelGateway(agentBackend, normaliser, messageService, channelService,
                crossTenantChannelStore, event -> publisher.publishEvent(event),
                event -> publisher.publishEvent(event), deliveryConfig, crossTenantMessageStore,
                membershipService, tracerSupplier, tracingConfig);
        messageService.setChannelGateway(gw);
        return gw;
    }

    @Bean
    public DeliveryBatchExecutor deliveryBatchExecutor(CrossTenantMessageStore messageStore,
                                                       CrossTenantChannelStore channelStore,
                                                       DeliveryCursorStore cursorStore,
                                                       DeliveryConfig config,
                                                       ChannelMembershipStore membershipStore,
                                                       Optional<Tracer> tracer,
                                                       QhorusTracingConfig tracingConfig) {
        return new DeliveryBatchExecutor(messageStore, channelStore, cursorStore, config, membershipStore,
                tracer.map(t -> (Supplier<Tracer>) () -> t).orElse(null), tracingConfig);
    }

    @Bean
    public DeliveryService deliveryService(DeliverySignalQueue signalQueue, DeliveryConfig config,
                                           ChannelGateway gateway, DeliveryBatchExecutor batchExecutor,
                                           DeliveryCursorStore cursorStore, CrossTenantMessageStore messageStore,
                                           CrossTenantChannelStore channelStore,
                                           ChannelMembershipStore channelMembershipStore,
                                           Optional<MeterRegistry> meterRegistry) {
        return new DeliveryService(signalQueue, config, gateway,
                Executors.newVirtualThreadPerTaskExecutor(), batchExecutor, cursorStore,
                messageStore, channelStore, channelMembershipStore, meterRegistry.orElse(null));
    }

    // ── Message services ──────────────────────────────────────────────────

    @Bean
    public CommitmentService commitmentService(CommitmentStore store, ApplicationEventPublisher publisher,
                                               Optional<Tracer> tracer, QhorusTracingConfig tracingConfig) {
        return new CommitmentService(store, event -> publisher.publishEvent(event),
                event -> { try { publisher.publishEvent(event); } catch (Exception ignored) {} },
                tracer.map(t -> (Supplier<Tracer>) () -> t).orElse(null), tracingConfig);
    }

    @Bean
    public EnforcementExecutor enforcementExecutor(io.casehub.qhorus.api.message.MessageDispatcher messageDispatcher,
                                                    ChannelService channelService, CommitmentService commitmentService,
                                                    ApplicationEventPublisher publisher, ObjectMapper objectMapper) {
        return new EnforcementExecutor(messageDispatcher, channelService, commitmentService,
                event -> publisher.publishEvent(event), objectMapper);
    }

    @Bean
    public ReactionService reactionService(ReactionStore reactionStore, ApplicationEventPublisher publisher,
                                           CurrentPrincipal currentPrincipal) {
        return new ReactionService(reactionStore, event -> publisher.publishEvent(event), currentPrincipal);
    }

    @Bean
    public RoutingBridge routingBridge(Optional<io.casehub.eidos.api.AgentRegistry> agentRegistry,
                                      Optional<io.casehub.eidos.api.AgentSelector> agentSelector,
                                      Optional<io.casehub.ledger.core.trust.TrustGateService> trustGateService,
                                      Optional<ActorCapacityView> capacityView, QhorusConfig config) {
        return new RoutingBridge(agentRegistry.orElse(null), agentSelector.orElse(null),
                trustGateService.orElse(null), capacityView.orElse(null), config);
    }

    @Bean
    public ProjectionRegistry projectionRegistry(List<RenderableProjection<?>> bundles) {
        return new ProjectionRegistry(bundles);
    }

    @Bean
    public ProtocolRegistry protocolRegistry(List<ChannelProtocol> protocols) {
        return new ProtocolRegistry(protocols);
    }

    @Bean
    public MessageService messageService(ChannelService channelService, CrossTenantChannelStore crossTenantChannelStore,
                                         CurrentPrincipal currentPrincipal, MessageStore messageStore,
                                         CommitmentService commitmentService, MessageTypePolicy messageTypePolicy,
                                         RateLimiter rateLimiter, QhorusConfig config,
                                         ObligorTrustPolicy obligorTrustPolicy,
                                         TransactionSynchronizationRegistry tsr,
                                         InstanceService instanceService, DeliverySignalQueue deliverySignalQueue,
                                         TopicService topicService,
                                         CorrelationIntegrityChecker correlationIntegrityChecker,
                                         ProtocolRegistry protocolRegistry, CommitmentStore commitmentStore,
                                         ChannelActivityBroadcaster broadcaster,
                                         Optional<Tracer> tracer, QhorusTracingConfig tracingConfig,
                                         EnforcementExecutor enforcementExecutor, RoutingBridge routingBridge,
                                         ChannelMembershipStore channelMembershipStore) {
        Supplier<Tracer> tracerSupplier = tracer.map(t -> (Supplier<Tracer>) () -> t).orElse(null);
        return new MessageService(channelService, crossTenantChannelStore, currentPrincipal,
                messageStore, commitmentService, messageTypePolicy, rateLimiter, config,
                obligorTrustPolicy, tsr, instanceService, deliverySignalQueue, topicService,
                correlationIntegrityChecker, protocolRegistry, commitmentStore, broadcaster,
                tracerSupplier, tracingConfig, enforcementExecutor, routingBridge,
                (channelName, channelId, tenancyId, message) -> {},
                (channelName, channelId, tenancyId, message) -> {},
                (dispatch, messageId, commitmentId, occurredAt, routingOutcome) ->
                        new io.casehub.qhorus.runtime.ledger.LedgerWriteOutcome(null, null, null),
                channelMembershipStore, config.correction().maxPerMessage());
    }

    // ── Strip classes ─────────────────────────────────────────────────────

    @Bean
    public InstanceService instanceService(InstanceStore instanceStore) {
        return new InstanceService(instanceStore);
    }

    @Bean
    public DataService dataService(DataStore dataStore) { return new DataService(dataStore); }

    @Bean
    public TopicService topicService(TopicStore topicStore, MessageStore messageStore,
                                     CommitmentStore commitmentStore, CurrentPrincipal currentPrincipal) {
        return new TopicService(topicStore, messageStore, commitmentStore, currentPrincipal);
    }

    @Bean
    public CorrelationIntegrityChecker correlationIntegrityChecker(CommitmentStore commitmentStore,
                                                                   MessageStore messageStore) {
        return new CorrelationIntegrityChecker(commitmentStore, messageStore);
    }

    @Bean
    public RateLimiter rateLimiter() { return new RateLimiter(); }

    @Bean
    public DeliverySignalQueue deliverySignalQueue() { return new DeliverySignalQueue(); }

    @Bean
    public EvidentialChecker evidentialChecker(DataStore dataStore, MessageStore messageStore,
                                              CommitmentStore commitmentStore) {
        return new EvidentialChecker(dataStore, messageStore, commitmentStore);
    }

    // ── Capacity ──────────────────────────────────────────────────────────

    @Bean
    public RedistributionDelegate redistributionDelegate(ChannelSummaryService summaryService,
                                                          MessageService messageService, RoutingBridge routingBridge,
                                                          CrossTenantChannelStore channelStore, MessageStore messageStore,
                                                          ApplicationEventPublisher publisher, QhorusConfig config) {
        return new RedistributionDelegate(summaryService, messageService, routingBridge, channelStore,
                messageStore, tenancyId -> {}, event -> publisher.publishEvent(event),
                config.routing().defaultTrustThreshold());
    }

    @Bean
    public QhorusRedistributionExecutor redistributionExecutor(RedistributionDelegate delegate,
                                                                RedistributionPolicy policy,
                                                                CrossTenantCommitmentStore commitmentStore) {
        return new QhorusRedistributionExecutor(delegate, policy, commitmentStore,
                actorId -> Duration.ofDays(365));
    }

    // ── Ledger ────────────────────────────────────────────────────────────

    @Bean
    public ReviewerResolver reviewerResolver(ChannelStore channelStore, InstanceService instanceService,
                                             ApplicationEventPublisher publisher) {
        return new ReviewerResolver(channelStore, instanceService, event -> publisher.publishEvent(event));
    }

    // ── Watchdog ──────────────────────────────────────────────────────────

    @Bean
    public WatchdogEvaluationService watchdogEvaluationService(
            QhorusConfig config, MessageService messageService,
            WatchdogStore watchdogStore, CrossTenantChannelStore crossTenantChannelStore,
            CrossTenantMessageStore crossTenantMessageStore,
            CrossTenantCommitmentStore crossTenantCommitmentStore,
            CrossTenantWatchdogStore crossTenantWatchdogStore,
            InstanceStore instanceStore, ApplicationEventPublisher publisher,
            WatchdogEvaluationService.ContextPressureQuery contextPressureQuery,
            ChannelMembershipStore channelMembershipStore, ChannelService channelService,
            InstanceService instanceService, CommitmentService commitmentService,
            ObjectMapper objectMapper) {
        return new WatchdogEvaluationService(config, messageService, watchdogStore,
                crossTenantChannelStore, crossTenantMessageStore, crossTenantCommitmentStore,
                crossTenantWatchdogStore, instanceStore, event -> publisher.publishEvent(event),
                contextPressureQuery, channelMembershipStore, channelService, instanceService,
                commitmentService, objectMapper);
    }

    // ── Protocol implementations ──────────────────────────────────────────

    @Bean
    public RoundRobinProtocol roundRobinProtocol() { return new RoundRobinProtocol(); }

    @Bean
    public ContributionRequiredProtocol contributionRequiredProtocol(QhorusConfig config) {
        return new ContributionRequiredProtocol(config.protocol().contributionRequired().maxConsecutive());
    }

    @Bean
    public RequestResponseProtocol requestResponseProtocol(QhorusConfig config) {
        return new RequestResponseProtocol(config.protocol().requestResponse().maxOpenQueries());
    }

    @Bean
    public TaskCompletionProtocol taskCompletionProtocol(QhorusConfig config) {
        return new TaskCompletionProtocol(config.protocol().taskCompletion().maxOpenCommands());
    }
}
