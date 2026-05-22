package io.casehub.qhorus.message;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.casehub.platform.api.identity.ActorType;
import io.casehub.platform.api.identity.ActorTypeResolver;
import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.casehub.qhorus.api.message.DispatchResult;
import io.casehub.qhorus.api.message.MessageDispatch;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.api.message.MessageTypeViolationException;
import io.casehub.qhorus.runtime.channel.ChannelService;
import io.casehub.qhorus.runtime.message.MessageService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class MessageServiceTypeEnforcementTest {

    @Inject
    MessageService messageService;

    @Inject
    ChannelService channelService;

    /** Helper: create a channel and return its ID, committed. */
    private UUID createChannel(String name, String allowedTypes) {
        UUID[] id = new UUID[1];
        QuarkusTransaction.requiringNew().run(() -> {
            var ch = channelService.create(name, "Test channel", ChannelSemantic.APPEND,
                    null, null, null, null, null, allowedTypes);
            id[0] = ch.id;
        });
        return id[0];
    }

    /** Helper: create an open channel (no type constraint) and return its ID, committed. */
    private UUID createOpenChannel(String name) {
        UUID[] id = new UUID[1];
        QuarkusTransaction.requiringNew().run(() -> {
            var ch = channelService.create(name, "Open channel", ChannelSemantic.APPEND, null);
            id[0] = ch.id;
        });
        return id[0];
    }

    @Test
    void serverSide_rejectsDisallowedType_bypassingMcpTool() {
        String name = "server-enforce-" + System.nanoTime();
        UUID channelId = createChannel(name, "EVENT");

        assertThrows(MessageTypeViolationException.class,
                () -> QuarkusTransaction.requiringNew().run(() -> messageService.dispatch(
                        MessageDispatch.builder()
                                .channelId(channelId)
                                .sender("agent-1")
                                .type(MessageType.QUERY)
                                .content("text")
                                .correlationId(UUID.randomUUID().toString())
                                .actorType(ActorTypeResolver.resolve("agent-1"))
                                .build())));
    }

    @Test
    void serverSide_permitsAllowedType() {
        String name = "server-allow-" + System.nanoTime();
        UUID channelId = createChannel(name, "EVENT");

        assertDoesNotThrow(
                () -> QuarkusTransaction.requiringNew().run(() -> messageService.dispatch(
                        MessageDispatch.builder()
                                .channelId(channelId)
                                .sender("agent-1")
                                .type(MessageType.EVENT)
                                .content("{\"tool\":\"read\"}")
                                .actorType(ActorTypeResolver.resolve("agent-1"))
                                .build())));
    }

    @Test
    void serverSide_permitsAllTypes_whenConstraintIsNull() {
        String name = "server-open-" + System.nanoTime();
        UUID channelId = createOpenChannel(name);

        // Types that need no prior context: QUERY, COMMAND, STATUS, EVENT
        for (MessageType t : new MessageType[]{MessageType.QUERY, MessageType.COMMAND,
                MessageType.STATUS, MessageType.EVENT}) {
            final MessageType type = t;
            String corrId = type.requiresCorrelationId() ? UUID.randomUUID().toString() : null;
            assertDoesNotThrow(
                    () -> QuarkusTransaction.requiringNew().run(() -> messageService.dispatch(
                            MessageDispatch.builder()
                                    .channelId(channelId)
                                    .sender("agent-1")
                                    .type(type)
                                    .content("content")
                                    .correlationId(corrId)
                                    .actorType(ActorTypeResolver.resolve("agent-1"))
                                    .build())),
                    "Expected " + t + " to be permitted on open channel");
        }

        // RESPONSE requires inReplyTo — first send a QUERY to get an ID to reply to
        String queryCorr = UUID.randomUUID().toString();
        DispatchResult[] queryResult = new DispatchResult[1];
        QuarkusTransaction.requiringNew().run(() -> {
            queryResult[0] = messageService.dispatch(MessageDispatch.builder()
                    .channelId(channelId)
                    .sender("agent-2")
                    .type(MessageType.QUERY)
                    .content("question?")
                    .correlationId(queryCorr)
                    .actorType(ActorType.AGENT)
                    .build());
        });
        assertDoesNotThrow(
                () -> QuarkusTransaction.requiringNew().run(() -> messageService.dispatch(
                        MessageDispatch.builder()
                                .channelId(channelId)
                                .sender("agent-1")
                                .type(MessageType.RESPONSE)
                                .content("answer")
                                .correlationId(queryCorr)
                                .inReplyTo(queryResult[0].messageId())
                                .actorType(ActorType.AGENT)
                                .build())),
                "Expected RESPONSE to be permitted on open channel");

        // DONE, DECLINE, FAILURE require inReplyTo + correlationId — send a COMMAND first
        String cmdCorr = UUID.randomUUID().toString();
        DispatchResult[] cmdResult = new DispatchResult[1];
        QuarkusTransaction.requiringNew().run(() -> {
            cmdResult[0] = messageService.dispatch(MessageDispatch.builder()
                    .channelId(channelId)
                    .sender("orchestrator")
                    .type(MessageType.COMMAND)
                    .content("do task")
                    .correlationId(cmdCorr)
                    .actorType(ActorType.AGENT)
                    .build());
        });

        // DONE resolves the commitment
        assertDoesNotThrow(
                () -> QuarkusTransaction.requiringNew().run(() -> messageService.dispatch(
                        MessageDispatch.builder()
                                .channelId(channelId)
                                .sender("agent-1")
                                .type(MessageType.DONE)
                                .content("completed")
                                .correlationId(cmdCorr)
                                .inReplyTo(cmdResult[0].messageId())
                                .actorType(ActorType.AGENT)
                                .build())),
                "Expected DONE to be permitted on open channel");

        // New COMMAND for DECLINE
        String cmdCorr2 = UUID.randomUUID().toString();
        DispatchResult[] cmdResult2 = new DispatchResult[1];
        QuarkusTransaction.requiringNew().run(() -> {
            cmdResult2[0] = messageService.dispatch(MessageDispatch.builder()
                    .channelId(channelId)
                    .sender("orchestrator")
                    .type(MessageType.COMMAND)
                    .content("another task")
                    .correlationId(cmdCorr2)
                    .actorType(ActorType.AGENT)
                    .build());
        });

        assertDoesNotThrow(
                () -> QuarkusTransaction.requiringNew().run(() -> messageService.dispatch(
                        MessageDispatch.builder()
                                .channelId(channelId)
                                .sender("agent-1")
                                .type(MessageType.DECLINE)
                                .content("out of scope")
                                .correlationId(cmdCorr2)
                                .inReplyTo(cmdResult2[0].messageId())
                                .actorType(ActorType.AGENT)
                                .build())),
                "Expected DECLINE to be permitted on open channel");

        // New COMMAND for FAILURE
        String cmdCorr3 = UUID.randomUUID().toString();
        DispatchResult[] cmdResult3 = new DispatchResult[1];
        QuarkusTransaction.requiringNew().run(() -> {
            cmdResult3[0] = messageService.dispatch(MessageDispatch.builder()
                    .channelId(channelId)
                    .sender("orchestrator")
                    .type(MessageType.COMMAND)
                    .content("risky task")
                    .correlationId(cmdCorr3)
                    .actorType(ActorType.AGENT)
                    .build());
        });

        assertDoesNotThrow(
                () -> QuarkusTransaction.requiringNew().run(() -> messageService.dispatch(
                        MessageDispatch.builder()
                                .channelId(channelId)
                                .sender("agent-1")
                                .type(MessageType.FAILURE)
                                .content("it broke")
                                .correlationId(cmdCorr3)
                                .inReplyTo(cmdResult3[0].messageId())
                                .actorType(ActorType.AGENT)
                                .build())),
                "Expected FAILURE to be permitted on open channel");

        // HANDOFF requires inReplyTo + correlationId + target
        String cmdCorr4 = UUID.randomUUID().toString();
        DispatchResult[] cmdResult4 = new DispatchResult[1];
        QuarkusTransaction.requiringNew().run(() -> {
            cmdResult4[0] = messageService.dispatch(MessageDispatch.builder()
                    .channelId(channelId)
                    .sender("orchestrator")
                    .type(MessageType.COMMAND)
                    .content("delegatable task")
                    .correlationId(cmdCorr4)
                    .actorType(ActorType.AGENT)
                    .build());
        });

        assertDoesNotThrow(
                () -> QuarkusTransaction.requiringNew().run(() -> messageService.dispatch(
                        MessageDispatch.builder()
                                .channelId(channelId)
                                .sender("agent-1")
                                .type(MessageType.HANDOFF)
                                .content("delegating")
                                .correlationId(cmdCorr4)
                                .inReplyTo(cmdResult4[0].messageId())
                                .target("instance:other-001")
                                .actorType(ActorType.AGENT)
                                .build())),
                "Expected HANDOFF to be permitted on open channel");
    }

    @Test
    void serverSide_violation_messageContainsChannelAndType() {
        String name = "server-msg-" + System.nanoTime();
        UUID channelId = createChannel(name, "QUERY,COMMAND");

        MessageTypeViolationException ex = assertThrows(MessageTypeViolationException.class,
                () -> QuarkusTransaction.requiringNew().run(() -> messageService.dispatch(
                        MessageDispatch.builder()
                                .channelId(channelId)
                                .sender("agent-1")
                                .type(MessageType.EVENT)
                                .content("{}")
                                .actorType(ActorTypeResolver.resolve("agent-1"))
                                .build())));
        assertTrue(ex.getMessage().contains(name), "Expected channel name in error: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("EVENT"), "Expected type in error: " + ex.getMessage());
    }

    @Test
    void serverSide_multiTypeConstraint_permitsAllListed() {
        String name = "server-multi-" + System.nanoTime();
        UUID channelId = createChannel(name, "QUERY,COMMAND");

        String corrId = UUID.randomUUID().toString();
        assertDoesNotThrow(
                () -> QuarkusTransaction.requiringNew().run(() -> messageService.dispatch(
                        MessageDispatch.builder()
                                .channelId(channelId)
                                .sender("agent-1")
                                .type(MessageType.COMMAND)
                                .content("do it")
                                .correlationId(corrId)
                                .actorType(ActorTypeResolver.resolve("agent-1"))
                                .build())));
    }
}
