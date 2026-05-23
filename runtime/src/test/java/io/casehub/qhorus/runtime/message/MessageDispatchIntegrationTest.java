package io.casehub.qhorus.runtime.message;

import static org.assertj.core.api.Assertions.*;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
import io.casehub.qhorus.api.message.CommitmentState;
import io.casehub.qhorus.runtime.message.CommitmentService;

@QuarkusTest
class MessageDispatchIntegrationTest {

    @Inject MessageService messageService;
    @Inject ChannelStore channelStore;
    @Inject CommitmentService commitmentService;

    @Test @TestTransaction
    void dispatch_command_with_deadline_persists_deadline() {
        final UUID channelId = createChannel("deadline-test-" + UUID.randomUUID());
        final Instant deadline = Instant.now().plus(Duration.ofHours(1)).truncatedTo(ChronoUnit.MILLIS);

        final DispatchResult result = messageService.dispatch(MessageDispatch.builder()
                .channelId(channelId).sender("orchestrator").type(MessageType.COMMAND)
                .content("do task").correlationId("corr-deadline-" + UUID.randomUUID())
                .deadline(deadline)
                .actorType(ActorType.SYSTEM).build());

        assertThat(messageService.findById(result.messageId()))
                .isPresent()
                .hasValueSatisfying(m -> assertThat(m.deadline).isEqualTo(deadline));
    }

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
    void dispatch_command_opens_commitment() {
        UUID channelId = createChannel("commitment-test-" + UUID.randomUUID());
        String corrId = "corr-commit-" + UUID.randomUUID();

        DispatchResult result = messageService.dispatch(MessageDispatch.builder()
                .channelId(channelId).sender("orchestrator").type(MessageType.COMMAND)
                .content("do task").correlationId(corrId)
                .actorType(ActorType.SYSTEM).build());

        assertThat(result.messageId()).isNotNull();
        // Commitment is opened as a side effect of dispatch — verifies the contract is explicit
        assertThat(commitmentService.findByCorrelationId(corrId))
                .isPresent()
                .hasValueSatisfying(c -> assertThat(c.state).isEqualTo(CommitmentState.OPEN));
    }

    @Test @TestTransaction
    void dispatch_event_with_correlationId_inherits_subjectId_from_root() {
        UUID channelId = createChannel("event-inherit-" + UUID.randomUUID());
        UUID subject = UUID.randomUUID();
        String corrId = "corr-event-inherit-" + UUID.randomUUID();

        // Seed a COMMAND with an explicit subjectId to act as the correlation root
        messageService.dispatch(MessageDispatch.builder()
                .channelId(channelId).sender("orchestrator").type(MessageType.COMMAND)
                .content("seed").correlationId(corrId).subjectId(subject)
                .actorType(ActorType.SYSTEM).build());

        // EVENT with same correlationId but no explicit subjectId should inherit from root
        // Verifies priority-2 inheritance path for EVENT (highest-volume type)
        DispatchResult event = messageService.dispatch(MessageDispatch.builder()
                .channelId(channelId).sender("system").type(MessageType.EVENT)
                .content("{\"tool_name\":\"probe\"}").correlationId(corrId)
                .actorType(ActorType.SYSTEM).build());

        assertThat(event.subjectId()).isEqualTo(subject);
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
        // causedByEntryId auto-linked from COMMAND's ledger entry.
        // Relies on REQUIRES_NEW cross-subtransaction visibility: LedgerWriteService.record() commits its
        // own subtransaction before MessageService.dispatch() returns, so the COMMAND entry is visible to
        // the DONE's ledger write. If the ledger is disabled via profile, ledgerEntryId is null and this assertion passes vacuously.
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

    // ── Enforcement — paused channel ─────────────────────────────────────────

    @Test @TestTransaction
    void dispatch_on_paused_channel_throws() {
        UUID channelId = createChannel("paused-test-" + UUID.randomUUID(), ch -> ch.paused = true);

        assertThatThrownBy(() -> messageService.dispatch(MessageDispatch.builder()
                .channelId(channelId).sender("agent-1").type(MessageType.COMMAND)
                .content("go").correlationId("corr-" + UUID.randomUUID())
                .actorType(ActorType.AGENT).build()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("paused");
    }

    // ── Enforcement — ACL ─────────────────────────────────────────────────────

    @Test @TestTransaction
    void dispatch_blocked_by_acl_throws() {
        UUID channelId = createChannel("acl-test-" + UUID.randomUUID(),
                ch -> ch.allowedWriters = "agent-allowed");

        assertThatThrownBy(() -> messageService.dispatch(MessageDispatch.builder()
                .channelId(channelId).sender("agent-blocked").type(MessageType.COMMAND)
                .content("go").correlationId("corr-" + UUID.randomUUID())
                .actorType(ActorType.AGENT).build()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not permitted");
    }

    @Test @TestTransaction
    void dispatch_allowed_by_acl_passes() {
        UUID channelId = createChannel("acl-pass-" + UUID.randomUUID(),
                ch -> ch.allowedWriters = "agent-allowed");

        assertThatNoException().isThrownBy(() -> messageService.dispatch(MessageDispatch.builder()
                .channelId(channelId).sender("agent-allowed").type(MessageType.COMMAND)
                .content("go").correlationId("corr-" + UUID.randomUUID())
                .actorType(ActorType.AGENT).build()));
    }

    @Test @TestTransaction
    void dispatch_event_bypasses_acl() {
        // EVENT messages bypass ACL — telemetry always flows
        UUID channelId = createChannel("event-acl-" + UUID.randomUUID(),
                ch -> ch.allowedWriters = "agent-allowed");

        assertThatNoException().isThrownBy(() -> messageService.dispatch(MessageDispatch.builder()
                .channelId(channelId).sender("agent-blocked").type(MessageType.EVENT)
                .content("{\"tool_name\":\"probe\"}").actorType(ActorType.SYSTEM).build()));
    }

    // ── Enforcement — LAST_WRITE (moved from QhorusMcpTools) ─────────────────

    @Test @TestTransaction
    void dispatch_last_write_same_sender_overwrites_in_place() {
        UUID channelId = createChannel("lw-test-" + UUID.randomUUID(),
                ch -> ch.semantic = ChannelSemantic.LAST_WRITE);
        String corrId = "corr-lw-" + UUID.randomUUID();

        DispatchResult first = messageService.dispatch(MessageDispatch.builder()
                .channelId(channelId).sender("writer").type(MessageType.COMMAND)
                .content("v1").correlationId(corrId).actorType(ActorType.AGENT).build());

        DispatchResult second = messageService.dispatch(MessageDispatch.builder()
                .channelId(channelId).sender("writer").type(MessageType.STATUS)
                .content("v2").correlationId(corrId).actorType(ActorType.AGENT).build());

        // Same message ID — overwrite, not insert
        assertThat(second.messageId()).isEqualTo(first.messageId());
        // Content updated
        assertThat(messageService.findById(second.messageId()))
                .isPresent()
                .hasValueSatisfying(m -> assertThat(m.content).isEqualTo("v2"));
    }

    @Test @TestTransaction
    void dispatch_last_write_different_sender_throws() {
        UUID channelId = createChannel("lw-block-" + UUID.randomUUID(),
                ch -> ch.semantic = ChannelSemantic.LAST_WRITE);

        messageService.dispatch(MessageDispatch.builder()
                .channelId(channelId).sender("writer-1").type(MessageType.COMMAND)
                .content("v1").correlationId("corr-lw-" + UUID.randomUUID()).actorType(ActorType.AGENT).build());

        assertThatThrownBy(() -> messageService.dispatch(MessageDispatch.builder()
                .channelId(channelId).sender("writer-2").type(MessageType.STATUS)
                .content("v2").correlationId("corr-lw-" + UUID.randomUUID()).actorType(ActorType.AGENT).build()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("LAST_WRITE");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private UUID createChannel(String name) {
        return createChannel(name, ch -> {});
    }

    private UUID createChannel(String name, java.util.function.Consumer<Channel> configure) {
        Channel ch = new Channel();
        ch.id = UUID.randomUUID();
        ch.name = name;
        ch.semantic = ChannelSemantic.APPEND;
        configure.accept(ch);
        channelStore.put(ch);
        return ch.id;
    }
}
