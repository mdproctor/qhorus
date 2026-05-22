package io.casehub.qhorus.runtime.message;

import static org.assertj.core.api.Assertions.*;

import java.util.UUID;
import org.junit.jupiter.api.Test;

import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.message.MessageDispatch;
import io.casehub.qhorus.api.message.MessageType;

class MessageDispatchBuilderTest {

    // ── Valid cases ───────────────────────────────────────────────────────────

    @Test void command_minimal_passes() {
        assertThatNoException().isThrownBy(() ->
            MessageDispatch.builder()
                .channelId(UUID.randomUUID()).sender("agent").type(MessageType.COMMAND)
                .content("do it").correlationId("c1").actorType(ActorType.AGENT).build());
    }

    @Test void done_with_required_fields_passes() {
        assertThatNoException().isThrownBy(() ->
            MessageDispatch.builder()
                .channelId(UUID.randomUUID()).sender("agent").type(MessageType.DONE)
                .content("done").correlationId("c1").inReplyTo(42L).actorType(ActorType.AGENT).build());
    }

    @Test void handoff_with_all_required_passes() {
        assertThatNoException().isThrownBy(() ->
            MessageDispatch.builder()
                .channelId(UUID.randomUUID()).sender("agent").type(MessageType.HANDOFF)
                .content("delegate").correlationId("c1").inReplyTo(42L).target("role:specialist")
                .actorType(ActorType.AGENT).build());
    }

    @Test void status_without_reply_passes() {
        assertThatNoException().isThrownBy(() ->
            MessageDispatch.builder()
                .channelId(UUID.randomUUID()).sender("agent").type(MessageType.STATUS)
                .content("progress").actorType(ActorType.AGENT).build());
    }

    // ── DONE/DECLINE/FAILURE require inReplyTo ────────────────────────────────

    @Test void done_without_inReplyTo_throws() {
        assertThatThrownBy(() ->
            MessageDispatch.builder()
                .channelId(UUID.randomUUID()).sender("a").type(MessageType.DONE)
                .correlationId("c1").actorType(ActorType.AGENT).build())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("DONE").hasMessageContaining("inReplyTo");
    }

    @Test void decline_without_inReplyTo_throws() {
        assertThatThrownBy(() ->
            MessageDispatch.builder()
                .channelId(UUID.randomUUID()).sender("a").type(MessageType.DECLINE)
                .correlationId("c1").actorType(ActorType.AGENT).build())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("DECLINE").hasMessageContaining("inReplyTo");
    }

    @Test void failure_without_inReplyTo_throws() {
        assertThatThrownBy(() ->
            MessageDispatch.builder()
                .channelId(UUID.randomUUID()).sender("a").type(MessageType.FAILURE)
                .correlationId("c1").actorType(ActorType.AGENT).build())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("FAILURE").hasMessageContaining("inReplyTo");
    }

    // ── DONE/DECLINE/FAILURE require correlationId ────────────────────────────

    @Test void done_without_correlationId_throws() {
        assertThatThrownBy(() ->
            MessageDispatch.builder()
                .channelId(UUID.randomUUID()).sender("a").type(MessageType.DONE)
                .inReplyTo(1L).actorType(ActorType.AGENT).build())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("DONE").hasMessageContaining("correlationId");
    }

    // ── RESPONSE requires inReplyTo + correlationId ──────────────────────────

    @Test void response_without_inReplyTo_throws() {
        assertThatThrownBy(() ->
            MessageDispatch.builder()
                .channelId(UUID.randomUUID()).sender("a").type(MessageType.RESPONSE)
                .content("answer").correlationId("c1").actorType(ActorType.AGENT).build())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("RESPONSE").hasMessageContaining("inReplyTo");
    }

    @Test void response_without_correlationId_throws() {
        assertThatThrownBy(() ->
            MessageDispatch.builder()
                .channelId(UUID.randomUUID()).sender("a").type(MessageType.RESPONSE)
                .content("answer").inReplyTo(1L).actorType(ActorType.AGENT).build())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("RESPONSE").hasMessageContaining("correlationId");
    }

    // ── HANDOFF requires inReplyTo + correlationId + target ──────────────────

    @Test void handoff_without_target_throws() {
        assertThatThrownBy(() ->
            MessageDispatch.builder()
                .channelId(UUID.randomUUID()).sender("a").type(MessageType.HANDOFF)
                .inReplyTo(1L).correlationId("c1").actorType(ActorType.AGENT).build())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("HANDOFF").hasMessageContaining("target");
    }

    @Test void handoff_without_correlationId_throws() {
        assertThatThrownBy(() ->
            MessageDispatch.builder()
                .channelId(UUID.randomUUID()).sender("a").type(MessageType.HANDOFF)
                .inReplyTo(1L).target("role:x").actorType(ActorType.AGENT).build())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("HANDOFF").hasMessageContaining("correlationId");
    }

    @Test void handoff_blank_target_throws() {
        assertThatThrownBy(() ->
            MessageDispatch.builder()
                .channelId(UUID.randomUUID()).sender("a").type(MessageType.HANDOFF)
                .inReplyTo(1L).correlationId("c1").target("  ").actorType(ActorType.AGENT).build())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("HANDOFF").hasMessageContaining("target");
    }
}
