package io.casehub.qhorus.runtime.message.protocol;

import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.api.message.MessageView;
import io.casehub.qhorus.api.spi.ChannelProtocol;
import io.casehub.qhorus.api.spi.ProtocolContext;
import io.casehub.qhorus.runtime.config.QhorusConfig;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;

@ApplicationScoped
public class ContributionRequiredProtocol implements ChannelProtocol {

    @Inject
    QhorusConfig config;

    int maxConsecutive;

    @PostConstruct
    void init() {
        maxConsecutive = config.protocol().contributionRequired().maxConsecutive();
    }

    @Override
    public String protocolName() {
        return "CONTRIBUTION_REQUIRED";
    }

    @Override
    public List<String> evaluate(ProtocolContext ctx) {
        List<String> participants = ctx.protocolParticipants().isEmpty()
                ? deriveParticipants(ctx.recentMessages())
                : ctx.protocolParticipants();
        if (participants.size() <= 1) return List.of();

        int consecutive = 0;
        for (int i = ctx.recentMessages().size() - 1; i >= 0; i--) {
            MessageView mv = ctx.recentMessages().get(i);
            if (mv.type() == MessageType.EVENT) continue;
            if (mv.sender().equals(ctx.sender())) consecutive++;
            else break;
        }

        if (consecutive + 1 < maxConsecutive) return List.of();

        List<String> missing = participants.stream()
                .filter(p -> !p.equals(ctx.sender()))
                .toList();
        if (missing.isEmpty()) return List.of();

        return List.of("[CONTRIBUTION_REQUIRED] '" + ctx.sender()
                + "' has sent " + (consecutive + 1)
                + " consecutive messages in channel '" + ctx.channelName()
                + "' without contributions from: " + String.join(", ", missing));
    }

    private List<String> deriveParticipants(List<MessageView> messages) {
        return messages.stream()
                .filter(m -> m.type() != MessageType.EVENT)
                .map(MessageView::sender)
                .distinct()
                .toList();
    }
}
