package io.casehub.qhorus.message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import jakarta.inject.Inject;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import io.casehub.platform.api.identity.ActorType;
import io.casehub.platform.api.identity.CurrentPrincipal;
import io.casehub.platform.api.identity.TenancyConstants;
import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.casehub.qhorus.api.message.MessageDispatch;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.api.channel.Channel;
import io.casehub.qhorus.runtime.message.MessageService;
import io.casehub.qhorus.api.store.ChannelStore;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.narayana.jta.QuarkusTransaction;

@QuarkusTest
class MessageDispatchTenancyTest {

    @Inject MessageService messageService;
    @Inject ChannelStore channelStore;
    @InjectMock CurrentPrincipal currentPrincipal;

    private Channel channel;

    @BeforeEach
    void setup() {
        Mockito.when(currentPrincipal.tenancyId()).thenReturn(TenancyConstants.DEFAULT_TENANT_ID);
        QuarkusTransaction.requiringNew().run(() -> {
            channel = channelStore.put(Channel.builder("dispatch-tenancy-" + UUID.randomUUID())
                    .id(UUID.randomUUID())
                    .semantic(ChannelSemantic.APPEND)
                    .tenancyId(TenancyConstants.DEFAULT_TENANT_ID)
                    .build());
        });
    }

    @Test
    void dispatch_normalPath_usesCurrentPrincipalTenancy() {
        Mockito.when(currentPrincipal.tenancyId()).thenReturn(TenancyConstants.DEFAULT_TENANT_ID);
        var result = messageService.dispatch(MessageDispatch.builder()
                .channelId(channel.id())
                .sender("agent-1")
                .type(MessageType.QUERY)
                .actorType(ActorType.AGENT)
                .build());
        assertThat(result).isNotNull();
    }

    @Test
    void dispatch_explicitTenancyId_usedAsIs() {
        // Even with different principal tenant, explicit tenancyId matches the channel
        Mockito.when(currentPrincipal.tenancyId()).thenReturn("some-other-tenant");
        var result = messageService.dispatch(MessageDispatch.builder()
                .channelId(channel.id())
                .sender("system:watchdog")
                .type(MessageType.STATUS)
                .actorType(ActorType.SYSTEM)
                .tenancyId(TenancyConstants.DEFAULT_TENANT_ID)  // explicit — matches channel
                .build());
        assertThat(result).isNotNull();
    }

    @Test
    void dispatch_crossTenantAttempt_rejected() {
        // Principal tenant doesn't match channel tenant
        Mockito.when(currentPrincipal.tenancyId()).thenReturn("wrong-tenant");
        assertThatThrownBy(() -> messageService.dispatch(MessageDispatch.builder()
                .channelId(channel.id())
                .sender("intruder")
                .type(MessageType.QUERY)
                .actorType(ActorType.AGENT)
                .build()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Cross-tenant dispatch rejected");
    }
}
