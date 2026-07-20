package io.casehub.qhorus.runtime.message.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.channel.ChannelCreateRequest;
import io.casehub.qhorus.api.message.DispatchResult;
import io.casehub.qhorus.api.message.MessageDispatch;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.channel.ChannelService;
import io.casehub.qhorus.runtime.instance.InstanceService;
import io.casehub.qhorus.runtime.message.MessageService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

@QuarkusTest
class ProtocolDispatchIntegrationTest {

    @Inject MessageService messageService;
    @Inject ChannelService channelService;
    @Inject InstanceService instanceService;

    @Test
    @Transactional
    void dispatch_withRoundRobinProtocol_producesAdvisory_whenOutOfTurn() {
        instanceService.register("agent-a", "Agent A", List.of());
        instanceService.register("agent-b", "Agent B", List.of());

        var ch = channelService.create(ChannelCreateRequest.builder("rr-test-" + UUID.randomUUID().toString().substring(0, 8))
                .description("round robin test")
                .protocols(List.of("ROUND_ROBIN"))
                .protocolParticipants(List.of("agent-a", "agent-b"))
                .build());

        DispatchResult r1 = messageService.dispatch(MessageDispatch.builder()
                .channelId(ch.id()).sender("agent-a").type(MessageType.STATUS)
                .content("first").actorType(ActorType.AGENT).build());
        assertThat(r1.advisories().stream().filter(a -> a.startsWith("[ROUND_ROBIN]")).toList()).isEmpty();

        DispatchResult r2 = messageService.dispatch(MessageDispatch.builder()
                .channelId(ch.id()).sender("agent-a").type(MessageType.STATUS)
                .content("second out of turn").actorType(ActorType.AGENT).build());
        assertThat(r2.advisories().stream().filter(a -> a.startsWith("[ROUND_ROBIN]")).toList()).hasSize(1);
        assertThat(r2.advisories().stream().filter(a -> a.startsWith("[ROUND_ROBIN]")).findFirst().orElse(""))
                .contains("expected 'agent-b'");
    }

    @Test
    @Transactional
    void dispatch_withContributionRequired_producesAdvisory_whenConsecutive() {
        var ch = channelService.create(ChannelCreateRequest.builder("cr-test-" + UUID.randomUUID().toString().substring(0, 8))
                .description("contribution test")
                .protocols(List.of("CONTRIBUTION_REQUIRED"))
                .protocolParticipants(List.of("agent-x", "agent-y"))
                .build());

        messageService.dispatch(MessageDispatch.builder()
                .channelId(ch.id()).sender("agent-x").type(MessageType.STATUS)
                .content("msg1").actorType(ActorType.AGENT).build());

        DispatchResult r2 = messageService.dispatch(MessageDispatch.builder()
                .channelId(ch.id()).sender("agent-x").type(MessageType.STATUS)
                .content("msg2 consecutive").actorType(ActorType.AGENT).build());
        assertThat(r2.advisories().stream().filter(a -> a.startsWith("[CONTRIBUTION_REQUIRED]")).toList()).hasSize(1);
    }

    @Test
    @Transactional
    void dispatch_withNoProtocols_producesNoProtocolAdvisory() {
        var ch = channelService.create(ChannelCreateRequest.builder("no-proto-" + UUID.randomUUID().toString().substring(0, 8))
                .description("no protocols")
                .build());

        DispatchResult r = messageService.dispatch(MessageDispatch.builder()
                .channelId(ch.id()).sender("agent-z").type(MessageType.STATUS)
                .content("hello").actorType(ActorType.AGENT).build());
        assertThat(r.advisories().stream()
                .filter(a -> a.startsWith("[ROUND_ROBIN]") || a.startsWith("[CONTRIBUTION_REQUIRED]")
                        || a.startsWith("[REQUEST_RESPONSE]") || a.startsWith("[TASK_COMPLETION]"))
                .toList()).isEmpty();
    }
}
