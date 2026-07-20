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
public class TaskCompletionProtocol implements ChannelProtocol {

    @Inject
    QhorusConfig config;

    int maxOpenCommands;

    @PostConstruct
    void init() {
        maxOpenCommands = config.protocol().taskCompletion().maxOpenCommands();
    }

    @Override
    public String protocolName() {
        return "TASK_COMPLETION";
    }

    @Override
    public List<String> evaluate(ProtocolContext ctx) {
        List<Commitment> openCommands = ctx.activeCommitments().stream()
                .filter(c -> c.messageType() == MessageType.COMMAND)
                .toList();
        if (openCommands.isEmpty()) return List.of();

        List<String> advisories = new ArrayList<>();
        if (ctx.incomingType() == MessageType.COMMAND && openCommands.size() >= maxOpenCommands) {
            advisories.add("[TASK_COMPLETION] " + openCommands.size()
                    + " open COMMANDs in channel '" + ctx.channelName()
                    + "' — consider resolving existing tasks");
        }

        boolean senderIsObligor = openCommands.stream()
                .anyMatch(c -> ctx.sender().equals(c.obligor()));
        if (senderIsObligor && ctx.incomingType() != MessageType.DONE
                && ctx.incomingType() != MessageType.FAILURE
                && ctx.incomingType() != MessageType.DECLINE) {
            advisories.add("[TASK_COMPLETION] you have an open obligation in channel '"
                    + ctx.channelName() + "' — consider sending DONE/FAILURE/DECLINE");
        }
        return advisories;
    }
}
