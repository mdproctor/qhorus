package io.casehub.qhorus.runtime.channel;

import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import io.casehub.platform.api.identity.CurrentPrincipal;
import io.casehub.qhorus.api.channel.Channel;
import io.casehub.qhorus.api.channel.ChannelConnectorBinding;
import io.casehub.qhorus.api.channel.ChannelCreateRequest;
import io.casehub.qhorus.api.gateway.ChannelRef;
import io.casehub.qhorus.api.store.ChannelBindingStore;
import io.casehub.qhorus.api.store.ChannelStore;
import io.casehub.qhorus.runtime.gateway.ChannelGateway;

@ApplicationScoped
class ChannelCreateHelper {

    @Inject
    ChannelStore channelStore;

    @Inject
    ChannelBindingStore channelBindingStore;

    @Inject
    ChannelGateway channelGateway;

    @Inject
    CurrentPrincipal currentPrincipal;

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    Channel createInNewTransaction(ChannelCreateRequest req, boolean autoCreated) {
        Channel channel = Channel.fromRequest(req, currentPrincipal.tenancyId());
        if (autoCreated) {
            channel = channel.toBuilder().autoCreated(true).build();
        }
        channel = channelStore.put(channel);

        if (req.hasConnectorBinding()) {
            channelBindingStore.findByKey(req.inboundConnectorId(), req.externalKey())
                    .ifPresent(existing -> {
                        throw new IllegalStateException(
                                "Connector binding already exists for connector '"
                                + req.inboundConnectorId() + "' key '" + req.externalKey() + "'");
                    });
            channelBindingStore.put(new ChannelConnectorBinding(
                    channel.id(), req.inboundConnectorId(), req.externalKey(),
                    req.outboundConnectorId(), req.outboundDestination()));
        }

        channelGateway.initChannel(channel.id(), new ChannelRef(channel.id(), channel.name()));
        return channel;
    }
}
