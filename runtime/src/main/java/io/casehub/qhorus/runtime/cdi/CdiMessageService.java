package io.casehub.qhorus.runtime.cdi;

import io.casehub.platform.api.identity.CurrentPrincipal;
import io.casehub.qhorus.api.gateway.ChannelActivityBroadcaster;
import io.casehub.qhorus.api.spi.ObligorTrustPolicy;
import io.casehub.qhorus.api.store.CommitmentStore;
import io.casehub.qhorus.api.store.CrossTenantChannelStore;
import io.casehub.qhorus.api.store.MessageStore;
import io.casehub.qhorus.runtime.channel.ChannelService;
import io.casehub.qhorus.runtime.channel.RateLimiter;
import io.casehub.qhorus.runtime.config.QhorusConfig;
import io.casehub.qhorus.runtime.config.QhorusTracingConfig;
import io.casehub.qhorus.runtime.gateway.DeliverySignalQueue;
import io.casehub.qhorus.runtime.instance.InstanceService;
import io.casehub.qhorus.runtime.ledger.LedgerWriteService;
import io.casehub.qhorus.runtime.message.CommitmentService;
import io.casehub.qhorus.runtime.message.CorrelationIntegrityChecker;
import io.casehub.qhorus.runtime.message.EnforcementExecutor;
import io.casehub.qhorus.runtime.message.MessageObserverDispatcher;
import io.casehub.qhorus.runtime.message.MessageService;
import io.casehub.qhorus.runtime.message.MessageTypePolicy;
import io.casehub.qhorus.runtime.message.RoutingBridge;
import io.casehub.qhorus.runtime.message.TopicService;
import io.casehub.qhorus.runtime.message.protocol.ProtocolRegistry;
import io.opentelemetry.api.trace.Tracer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.transaction.TransactionSynchronizationRegistry;
import jakarta.transaction.Transactional;

@ApplicationScoped
@Transactional
public class CdiMessageService extends MessageService {

    @Inject
    public CdiMessageService(ChannelService channelService,
                             CrossTenantChannelStore crossTenantChannelStore,
                             CurrentPrincipal currentPrincipal,
                             MessageStore messageStore,
                             CommitmentService commitmentService,
                             MessageTypePolicy messageTypePolicy,
                             RateLimiter rateLimiter,
                             QhorusConfig config,
                             ObligorTrustPolicy obligorTrustPolicy,
                             TransactionSynchronizationRegistry tsr,
                             InstanceService instanceService,
                             DeliverySignalQueue deliverySignalQueue,
                             TopicService topicService,
                             CorrelationIntegrityChecker correlationIntegrityChecker,
                             ProtocolRegistry protocolRegistry,
                             CommitmentStore commitmentStore,
                             ChannelActivityBroadcaster broadcaster,
                             Instance<Tracer> tracerInstance,
                             QhorusTracingConfig tracingConfig,
                             EnforcementExecutor enforcementExecutor,
                             RoutingBridge routingBridge,
                             @Any Instance<io.casehub.qhorus.api.gateway.MessageObserver> observers,
                             LedgerWriteService ledgerWriteService,
                             io.casehub.qhorus.api.store.ChannelMembershipStore channelMembershipStore) {
        super(channelService, crossTenantChannelStore, currentPrincipal,
                messageStore, commitmentService, messageTypePolicy, rateLimiter, config,
                obligorTrustPolicy, tsr, instanceService, deliverySignalQueue, topicService,
                correlationIntegrityChecker, protocolRegistry, commitmentStore, broadcaster,
                tracerInstance.isResolvable() ? tracerInstance::get : null, tracingConfig,
                enforcementExecutor, routingBridge,
                (channelName, channelId, tenancyId, message) ->
                        MessageObserverDispatcher.dispatch(
                                channelName, channelId, tenancyId, message, observers.handles(), tsr),
                (channelName, channelId, tenancyId, message) ->
                        MessageObserverDispatcher.dispatchClusterOnly(
                                channelName, channelId, tenancyId, message, observers.handles()),
                (dispatch, messageId, commitmentId, occurredAt, routingOutcome) ->
                        ledgerWriteService.record(dispatch, messageId, commitmentId, occurredAt, routingOutcome),
                channelMembershipStore,
                config.correction().maxPerMessage());
    }

    CdiMessageService() {}
}
