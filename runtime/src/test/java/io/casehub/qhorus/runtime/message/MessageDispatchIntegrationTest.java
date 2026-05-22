package io.casehub.qhorus.runtime.message;

import static org.assertj.core.api.Assertions.*;

import java.util.UUID;
import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.TestTransaction;

import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.message.DispatchResult;
import io.casehub.qhorus.api.message.MessageDispatch;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.channel.Channel;
import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.casehub.qhorus.runtime.store.ChannelStore;

@QuarkusTest
class MessageDispatchIntegrationTest {

    @Inject MessageService messageService;
    @Inject ChannelStore channelStore;

    @Test @TestTransaction
    void dispatch_command_returns_DispatchResult_with_messageId() {
        UUID channelId = createChannel("dispatch-test-" + UUID.randomUUID());

        MessageDispatch d = MessageDispatch.builder()
                .channelId(channelId).sender("agent-1").type(MessageType.COMMAND)
                .content("analyse this").correlationId("corr-1")
                .actorType(ActorType.AGENT).build();

        DispatchResult result = messageService.dispatch(d);

        assertThat(result.messageId()).isNotNull();
        assertThat(result.channelId()).isEqualTo(channelId);
        assertThat(result.sender()).isEqualTo("agent-1");
        assertThat(result.type()).isEqualTo(MessageType.COMMAND);
        assertThat(result.correlationId()).isEqualTo("corr-1");
    }

    @Test @TestTransaction
    void dispatch_with_explicit_subjectId_echoes_resolved_subjectId() {
        UUID channelId = createChannel("subject-test-" + UUID.randomUUID());
        UUID subject = UUID.randomUUID();

        DispatchResult result = messageService.dispatch(MessageDispatch.builder()
                .channelId(channelId).sender("agent-1").type(MessageType.COMMAND)
                .content("work").correlationId("corr-2").subjectId(subject)
                .actorType(ActorType.AGENT).build());

        assertThat(result.subjectId()).isEqualTo(subject);
    }

    @Test @TestTransaction
    void dispatch_done_inherits_subjectId_from_command() {
        UUID channelId = createChannel("inherit-test-" + UUID.randomUUID());
        UUID subject = UUID.randomUUID();

        DispatchResult command = messageService.dispatch(MessageDispatch.builder()
                .channelId(channelId).sender("orchestrator").type(MessageType.COMMAND)
                .content("do task").correlationId("corr-3").subjectId(subject)
                .actorType(ActorType.SYSTEM).build());

        DispatchResult done = messageService.dispatch(MessageDispatch.builder()
                .channelId(channelId).sender("worker").type(MessageType.DONE)
                .content("done").correlationId("corr-3").inReplyTo(command.messageId())
                .actorType(ActorType.AGENT).build());

        // subjectId propagated from COMMAND via correlation root lookup
        assertThat(done.subjectId()).isEqualTo(subject);
        // causedByEntryId auto-linked from COMMAND's ledger entry
        assertThat(done.causedByEntryId()).isEqualTo(command.ledgerEntryId());
    }

    @Test @TestTransaction
    void dispatch_event_without_correlationId_falls_back_to_channelId_as_subject() {
        UUID channelId = createChannel("event-test-" + UUID.randomUUID());

        DispatchResult result = messageService.dispatch(MessageDispatch.builder()
                .channelId(channelId).sender("system").type(MessageType.EVENT)
                .content("{\"tool_name\":\"test\"}").actorType(ActorType.SYSTEM).build());

        assertThat(result.subjectId()).isEqualTo(channelId);
        assertThat(result.causedByEntryId()).isNull();
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
