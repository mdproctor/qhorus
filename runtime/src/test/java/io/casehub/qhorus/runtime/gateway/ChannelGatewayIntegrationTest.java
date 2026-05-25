package io.casehub.qhorus.runtime.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.TestTransaction;

import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.casehub.qhorus.api.gateway.ChannelRef;
import io.casehub.qhorus.api.gateway.HumanParticipatingChannelBackend;
import io.casehub.qhorus.api.gateway.InboundHumanMessage;
import io.casehub.qhorus.api.gateway.InboundNormaliser;
import io.casehub.qhorus.api.gateway.NormalisedMessage;
import io.casehub.qhorus.api.gateway.OutboundMessage;
import io.casehub.qhorus.api.message.CommitmentState;
import io.casehub.qhorus.api.message.DispatchResult;
import io.casehub.qhorus.api.message.MessageDispatch;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.channel.Channel;
import io.casehub.qhorus.runtime.message.CommitmentService;
import io.casehub.qhorus.runtime.message.MessageService;
import io.casehub.qhorus.runtime.store.ChannelStore;

@QuarkusTest
class ChannelGatewayIntegrationTest {

    @Inject ChannelGateway channelGateway;
    @Inject MessageService messageService;
    @Inject CommitmentService commitmentService;
    @Inject ChannelStore channelStore;

    /**
     * Verifies the end-to-end scenario enabled by per-backend normalisation:
     * a human RESPONSE (via receiveHumanMessage with a custom normaliser carrying inReplyTo)
     * correctly fulfils the Commitment opened by the preceding COMMAND.
     */
    @Test @TestTransaction
    void receiveHumanMessage_RESPONSE_with_inReplyTo_fulfils_commitment() {
        String channelName = "normaliser-int-test-" + UUID.randomUUID();
        UUID channelId = createChannel(channelName);
        channelGateway.initChannel(channelId, new ChannelRef(channelId, channelName));

        String corrId = "corr-int-" + UUID.randomUUID();
        DispatchResult cmd = messageService.dispatch(MessageDispatch.builder()
                .channelId(channelId).sender("orchestrator").type(MessageType.COMMAND)
                .content("analyse data").correlationId(corrId).actorType(ActorType.SYSTEM).build());

        final long cmdMsgId = cmd.messageId();

        HumanParticipatingChannelBackend humanBackend = new HumanParticipatingChannelBackend() {
            @Override public String backendId()    { return "test-human-backend"; }
            @Override public ActorType actorType() { return ActorType.HUMAN; }
            @Override public void open(ChannelRef ch, Map<String, String> m) {}
            @Override public void post(ChannelRef ch, OutboundMessage msg)   {}
            @Override public void close(ChannelRef ch) {}
            @Override public InboundNormaliser normaliser() {
                return (ch, raw) -> new NormalisedMessage(
                        MessageType.RESPONSE, raw.content(),
                        "human:" + raw.externalSenderId(),
                        raw.correlationId(), cmdMsgId, null, null);
            }
        };
        channelGateway.registerBackend(channelId, humanBackend, "human_participating");

        InboundHumanMessage response = new InboundHumanMessage(
                "user-42", "Analysis complete.", Instant.now(), Map.of(), corrId, cmdMsgId);
        channelGateway.receiveHumanMessage(new ChannelRef(channelId, channelName), response);

        assertThat(commitmentService.findByCorrelationId(corrId))
                .isPresent()
                .hasValueSatisfying(c ->
                        assertThat(c.state).isEqualTo(CommitmentState.FULFILLED));
    }

    private UUID createChannel(String name) {
        Channel ch = new Channel();
        ch.id = UUID.randomUUID();
        ch.name = name;
        ch.semantic = ChannelSemantic.APPEND;
        channelStore.put(ch);
        return ch.id;
    }
}
