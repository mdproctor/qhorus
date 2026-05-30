package io.casehub.qhorus.runtime.message;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.UUID;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.casehub.qhorus.api.message.MessageDispatch;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.api.spi.ObligorTrustContext;
import io.casehub.qhorus.api.spi.ObligorTrustPolicy;
import io.casehub.qhorus.runtime.channel.Channel;
import io.casehub.qhorus.runtime.store.ChannelStore;
import io.quarkus.test.InjectMock;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;

/**
 * Verifies that {@link ObligorTrustPolicy} is an injectable SPI that MessageService
 * delegates to, passing a fully-populated {@link ObligorTrustContext}.
 * Refs #213
 */
@QuarkusTest
class ObligorTrustPolicySpiTest {

    @Inject
    MessageService messageService;

    @Inject
    ChannelStore channelStore;

    @InjectMock
    ObligorTrustPolicy obligorTrustPolicy;

    private Channel appendChannel(String name) {
        Channel ch = new Channel();
        ch.name = name;
        ch.semantic = ChannelSemantic.APPEND;
        return channelStore.put(ch);
    }

    @Test
    @TestTransaction
    void command_withNamedTarget_invokesPolicyWithFullContext() {
        Channel ch = appendChannel("spi-ctx-" + UUID.randomUUID());
        String target = "agent-" + UUID.randomUUID();
        when(obligorTrustPolicy.permits(any())).thenReturn(true);

        messageService.dispatch(MessageDispatch.builder()
                .channelId(ch.id)
                .sender("sender")
                .type(MessageType.COMMAND)
                .content("do something")
                .correlationId(UUID.randomUUID().toString())
                .target(target)
                .actorType(ActorType.AGENT)
                .build());

        ArgumentCaptor<ObligorTrustContext> captor = ArgumentCaptor.forClass(ObligorTrustContext.class);
        verify(obligorTrustPolicy).permits(captor.capture());
        ObligorTrustContext ctx = captor.getValue();
        assertEquals(target, ctx.obligorId());
        assertEquals(ch.id, ctx.channelId());
        assertEquals(ch.name, ctx.channelName());
    }

    @Test
    @TestTransaction
    void command_whenPolicyRejects_throwsIllegalStateException() {
        Channel ch = appendChannel("spi-reject-" + UUID.randomUUID());
        when(obligorTrustPolicy.permits(any())).thenReturn(false);

        assertThrows(IllegalStateException.class, () ->
                messageService.dispatch(MessageDispatch.builder()
                        .channelId(ch.id)
                        .sender("sender")
                        .type(MessageType.COMMAND)
                        .content("do something")
                        .correlationId(UUID.randomUUID().toString())
                        .target("some-agent")
                        .actorType(ActorType.AGENT)
                        .build()));
    }

    @Test
    @TestTransaction
    void command_whenPolicyPermits_dispatched() {
        Channel ch = appendChannel("spi-permit-" + UUID.randomUUID());
        when(obligorTrustPolicy.permits(any())).thenReturn(true);

        assertDoesNotThrow(() ->
                messageService.dispatch(MessageDispatch.builder()
                        .channelId(ch.id)
                        .sender("sender")
                        .type(MessageType.COMMAND)
                        .content("do something")
                        .correlationId(UUID.randomUUID().toString())
                        .target("some-agent")
                        .actorType(ActorType.AGENT)
                        .build()));
    }

    @Test
    @TestTransaction
    void nonCommandMessage_doesNotInvokePolicy() {
        Channel ch = appendChannel("spi-query-" + UUID.randomUUID());

        assertDoesNotThrow(() ->
                messageService.dispatch(MessageDispatch.builder()
                        .channelId(ch.id)
                        .sender("sender")
                        .type(MessageType.QUERY)
                        .content("question?")
                        .correlationId(UUID.randomUUID().toString())
                        .actorType(ActorType.AGENT)
                        .build()));

        verifyNoInteractions(obligorTrustPolicy);
    }

    @Test
    @TestTransaction
    void command_withRolePrefixedTarget_doesNotInvokePolicy() {
        Channel ch = appendChannel("spi-role-" + UUID.randomUUID());

        assertDoesNotThrow(() ->
                messageService.dispatch(MessageDispatch.builder()
                        .channelId(ch.id)
                        .sender("sender")
                        .type(MessageType.COMMAND)
                        .content("broadcast")
                        .correlationId(UUID.randomUUID().toString())
                        .target("role:specialist")
                        .actorType(ActorType.AGENT)
                        .build()));

        verifyNoInteractions(obligorTrustPolicy);
    }

    @Test
    @TestTransaction
    void command_withCapabilityPrefixedTarget_doesNotInvokePolicy() {
        Channel ch = appendChannel("spi-cap-" + UUID.randomUUID());

        assertDoesNotThrow(() ->
                messageService.dispatch(MessageDispatch.builder()
                        .channelId(ch.id)
                        .sender("sender")
                        .type(MessageType.COMMAND)
                        .content("broadcast")
                        .correlationId(UUID.randomUUID().toString())
                        .target("capability:analyst")
                        .actorType(ActorType.AGENT)
                        .build()));

        verifyNoInteractions(obligorTrustPolicy);
    }

    @Test
    @TestTransaction
    void command_withNullTarget_doesNotInvokePolicy() {
        Channel ch = appendChannel("spi-null-target-" + UUID.randomUUID());

        assertDoesNotThrow(() ->
                messageService.dispatch(MessageDispatch.builder()
                        .channelId(ch.id)
                        .sender("sender")
                        .type(MessageType.COMMAND)
                        .content("broadcast")
                        .correlationId(UUID.randomUUID().toString())
                        .actorType(ActorType.AGENT)
                        .build()));

        verifyNoInteractions(obligorTrustPolicy);
    }
}
