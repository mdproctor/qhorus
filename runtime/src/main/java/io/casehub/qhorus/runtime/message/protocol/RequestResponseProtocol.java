package io.casehub.qhorus.runtime.message.protocol;

import io.casehub.qhorus.api.message.Commitment;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.api.spi.ChannelProtocol;
import io.casehub.qhorus.api.spi.ProtocolContext;
import io.casehub.qhorus.runtime.config.QhorusConfig;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
public class RequestResponseProtocol implements ChannelProtocol {

    @Inject
    QhorusConfig config;

    int maxOpenQueries;

    @PostConstruct
    void init() {
        maxOpenQueries = config.protocol().requestResponse().maxOpenQueries();
    }

    @Override
    public String protocolName() {
        return "REQUEST_RESPONSE";
    }

    @Override
    public List<String> evaluate(ProtocolContext ctx) {
        List<Commitment> openQueries = ctx.activeCommitments().stream()
                .filter(c -> c.messageType() == MessageType.QUERY)
                .toList();
        if (openQueries.isEmpty()) return List.of();

        List<String> advisories = new ArrayList<>();
        if (ctx.incomingType() == MessageType.QUERY && openQueries.size() >= maxOpenQueries) {
            advisories.add("[REQUEST_RESPONSE] " + openQueries.size()
                    + " unanswered QUERYs in channel '" + ctx.channelName()
                    + "' — consider waiting for responses");
        }
        if (ctx.incomingType() != MessageType.RESPONSE && ctx.incomingType() != MessageType.QUERY) {
            advisories.add("[REQUEST_RESPONSE] channel '" + ctx.channelName()
                    + "' has open QUERYs awaiting RESPONSE");
        }
        return advisories;
    }
}
