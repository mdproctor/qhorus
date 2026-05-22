package io.casehub.qhorus.message;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Optional;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.casehub.platform.api.identity.ActorType;
import io.casehub.platform.api.identity.ActorTypeResolver;
import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.casehub.qhorus.api.message.DispatchResult;
import io.casehub.qhorus.api.message.MessageDispatch;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.channel.Channel;
import io.casehub.qhorus.runtime.channel.ChannelService;
import io.casehub.qhorus.runtime.message.Message;
import io.casehub.qhorus.runtime.message.MessageService;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class MessageServiceTest {

    @Inject
    ChannelService channelService;

    @Inject
    MessageService messageService;

    @Test
    @TestTransaction
    void sendMessagePersistsAllFields() {
        Channel ch = channelService.create("msg-test-1", "Test", ChannelSemantic.APPEND, null);

        DispatchResult result = messageService.dispatch(MessageDispatch.builder()
                .channelId(ch.id)
                .sender("alice")
                .type(MessageType.COMMAND)
                .content("Hello world")
                .correlationId("corr-123")
                .actorType(ActorTypeResolver.resolve("alice"))
                .build());

        assertNotNull(result.messageId());
        assertEquals(ch.id, result.channelId());
        assertEquals("alice", result.sender());
        assertEquals(MessageType.COMMAND, result.type());
        assertEquals("corr-123", result.correlationId());
        assertNull(result.inReplyTo());
        // Verify persisted message via findById for fields not in DispatchResult
        Message msg = messageService.findById(result.messageId()).orElseThrow();
        assertEquals("Hello world", msg.content);
        assertEquals(0, msg.replyCount);
        assertNotNull(msg.createdAt);
    }

    @Test
    @TestTransaction
    void sendReplyIncrementsParentReplyCount() {
        Channel ch = channelService.create("msg-test-2", "Test", ChannelSemantic.APPEND, null);
        DispatchResult request = messageService.dispatch(MessageDispatch.builder()
                .channelId(ch.id)
                .sender("alice")
                .type(MessageType.QUERY)
                .content("Question?")
                .correlationId("corr-456")
                .actorType(ActorTypeResolver.resolve("alice"))
                .build());

        messageService.dispatch(MessageDispatch.builder()
                .channelId(ch.id)
                .sender("bob")
                .type(MessageType.RESPONSE)
                .content("Answer!")
                .correlationId("corr-456")
                .inReplyTo(request.messageId())
                .actorType(ActorTypeResolver.resolve("bob"))
                .build());

        Message refreshed = messageService.findById(request.messageId()).orElseThrow();
        assertEquals(1, refreshed.replyCount);
    }

    @Test
    @TestTransaction
    void sendUpdatesChannelLastActivity() throws InterruptedException {
        Channel ch = channelService.create("msg-test-3", "Test", ChannelSemantic.APPEND, null);
        var activityBefore = ch.lastActivityAt;

        Thread.sleep(5);
        messageService.dispatch(MessageDispatch.builder()
                .channelId(ch.id)
                .sender("alice")
                .type(MessageType.STATUS)
                .content("working...")
                .actorType(ActorTypeResolver.resolve("alice"))
                .build());

        Channel updated = channelService.findByName("msg-test-3").orElseThrow();
        assertTrue(updated.lastActivityAt.isAfter(activityBefore),
                "channel.lastActivityAt should advance after send");
    }

    @Test
    @TestTransaction
    void pollAfterReturnsMessagesAfterGivenIdInAscendingOrder() {
        Channel ch = channelService.create("msg-test-4", "Test", ChannelSemantic.APPEND, null);
        DispatchResult m1 = messageService.dispatch(MessageDispatch.builder()
                .channelId(ch.id)
                .sender("alice")
                .type(MessageType.STATUS)
                .content("first")
                .actorType(ActorTypeResolver.resolve("alice"))
                .build());
        DispatchResult m2 = messageService.dispatch(MessageDispatch.builder()
                .channelId(ch.id)
                .sender("bob")
                .type(MessageType.STATUS)
                .content("second")
                .actorType(ActorTypeResolver.resolve("bob"))
                .build());
        DispatchResult m3 = messageService.dispatch(MessageDispatch.builder()
                .channelId(ch.id)
                .sender("carol")
                .type(MessageType.STATUS)
                .content("third")
                .actorType(ActorTypeResolver.resolve("carol"))
                .build());

        List<Message> after = messageService.pollAfter(ch.id, m1.messageId(), 10);

        assertEquals(2, after.size());
        assertEquals(m2.messageId(), after.get(0).id);
        assertEquals(m3.messageId(), after.get(1).id);
        // Ordering must be deterministic — guaranteed by ORDER BY id ASC in the query
        assertTrue(after.get(0).id < after.get(1).id, "messages must be in ascending ID order");
    }

    @Test
    @TestTransaction
    void pollAfterWithZeroReturnsAllMessagesInOrder() {
        Channel ch = channelService.create("msg-test-zero", "Test", ChannelSemantic.APPEND, null);
        messageService.dispatch(MessageDispatch.builder()
                .channelId(ch.id)
                .sender("alice")
                .type(MessageType.STATUS)
                .content("first")
                .actorType(ActorTypeResolver.resolve("alice"))
                .build());
        messageService.dispatch(MessageDispatch.builder()
                .channelId(ch.id)
                .sender("bob")
                .type(MessageType.STATUS)
                .content("second")
                .actorType(ActorTypeResolver.resolve("bob"))
                .build());

        List<Message> all = messageService.pollAfter(ch.id, 0L, 10);

        assertEquals(2, all.size());
        assertEquals("first", all.get(0).content);
        assertEquals("second", all.get(1).content);
    }

    @Test
    @TestTransaction
    void pollAfterExcludesEventMessages() {
        Channel ch = channelService.create("msg-test-5", "Test", ChannelSemantic.APPEND, null);
        DispatchResult m1 = messageService.dispatch(MessageDispatch.builder()
                .channelId(ch.id)
                .sender("alice")
                .type(MessageType.STATUS)
                .content("visible")
                .actorType(ActorTypeResolver.resolve("alice"))
                .build());
        messageService.dispatch(MessageDispatch.builder()
                .channelId(ch.id)
                .sender("system")
                .type(MessageType.EVENT)
                .content("telemetry")
                .actorType(ActorType.SYSTEM)
                .build());
        DispatchResult m3 = messageService.dispatch(MessageDispatch.builder()
                .channelId(ch.id)
                .sender("bob")
                .type(MessageType.STATUS)
                .content("also visible")
                .actorType(ActorTypeResolver.resolve("bob"))
                .build());

        List<Message> after = messageService.pollAfter(ch.id, m1.messageId(), 10);

        // EVENT type is observer-only — excluded from agent context
        assertEquals(1, after.size());
        assertEquals(m3.messageId(), after.get(0).id);
    }

    @Test
    @TestTransaction
    void findByCorrelationIdReturnsMatchingMessage() {
        Channel ch = channelService.create("msg-test-6", "Test", ChannelSemantic.APPEND, null);
        messageService.dispatch(MessageDispatch.builder()
                .channelId(ch.id)
                .sender("alice")
                .type(MessageType.QUERY)
                .content("payload")
                .correlationId("my-corr-id")
                .actorType(ActorTypeResolver.resolve("alice"))
                .build());

        Optional<Message> found = messageService.findByCorrelationId("my-corr-id");

        assertTrue(found.isPresent());
        assertEquals("my-corr-id", found.get().correlationId);
    }

    @Test
    @TestTransaction
    void findByCorrelationIdReturnsEmptyWhenNotFound() {
        Optional<Message> found = messageService.findByCorrelationId("no-such-corr");
        assertTrue(found.isEmpty());
    }

    // --- Pure enum tests — no DB interaction, no @TestTransaction overhead ---

    @Test
    void eventTypeIsNotAgentVisible() {
        assertFalse(MessageType.EVENT.isAgentVisible());
    }

    @Test
    void allOtherTypesAreAgentVisible() {
        assertTrue(MessageType.QUERY.isAgentVisible());
        assertTrue(MessageType.COMMAND.isAgentVisible());
        assertTrue(MessageType.RESPONSE.isAgentVisible());
        assertTrue(MessageType.STATUS.isAgentVisible());
        assertTrue(MessageType.DECLINE.isAgentVisible());
        assertTrue(MessageType.HANDOFF.isAgentVisible());
        assertTrue(MessageType.DONE.isAgentVisible());
        assertTrue(MessageType.FAILURE.isAgentVisible());
    }

}
