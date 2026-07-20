package io.casehub.qhorus.runtime.message.protocol;

import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.api.message.MessageView;
import io.casehub.qhorus.api.spi.ChannelProtocol;
import io.casehub.qhorus.api.spi.ProtocolContext;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

@ApplicationScoped
public class RoundRobinProtocol implements ChannelProtocol {

    @Override
    public String protocolName() {
        return "ROUND_ROBIN";
    }

    @Override
    public List<String> evaluate(ProtocolContext ctx) {
        List<String> participants = ctx.protocolParticipants();
        if (participants.size() <= 1) return List.of();
        if (!participants.contains(ctx.sender())) return List.of();

        String lastParticipantSender = null;
        for (int i = ctx.recentMessages().size() - 1; i >= 0; i--) {
            MessageView mv = ctx.recentMessages().get(i);
            if (mv.type() != MessageType.EVENT && participants.contains(mv.sender())) {
                lastParticipantSender = mv.sender();
                break;
            }
        }
        if (lastParticipantSender == null) return List.of();

        int lastIdx = participants.indexOf(lastParticipantSender);
        String expected = participants.get((lastIdx + 1) % participants.size());
        if (ctx.sender().equals(expected)) return List.of();

        return List.of("[ROUND_ROBIN] expected '" + expected
                + "' to speak next in channel '" + ctx.channelName()
                + "', got '" + ctx.sender() + "'");
    }
}
