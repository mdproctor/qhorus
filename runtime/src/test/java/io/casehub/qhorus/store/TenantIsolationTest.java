package io.casehub.qhorus.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import java.util.UUID;

import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import io.casehub.platform.api.identity.ActorType;
import io.casehub.platform.api.identity.CurrentPrincipal;
import io.casehub.platform.api.identity.TenancyConstants;
import io.casehub.qhorus.api.channel.Channel;
import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.casehub.qhorus.api.message.Commitment;
import io.casehub.qhorus.api.message.CommitmentState;
import io.casehub.qhorus.api.message.Message;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.api.store.ChannelStore;
import io.casehub.qhorus.api.store.CommitmentStore;
import io.casehub.qhorus.api.store.MessageStore;
import io.casehub.qhorus.api.store.query.ChannelQuery;
import io.casehub.qhorus.api.store.query.MessageQuery;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class TenantIsolationTest {

    private static final String TENANT_A = TenancyConstants.DEFAULT_TENANT_ID;
    private static final String TENANT_B = "tenant-b-isolation-test";

    @Inject ChannelStore channelStore;
    @Inject MessageStore messageStore;
    @Inject CommitmentStore commitmentStore;
    @InjectMock CurrentPrincipal currentPrincipal;

    @Test
    @Transactional
    void channel_createdInTenantA_notVisibleToTenantB() {
        Mockito.when(currentPrincipal.tenancyId()).thenReturn(TENANT_A);
        UUID chId = UUID.randomUUID();
        Channel ch = channelStore.put(Channel.builder("isolation-test-" + UUID.randomUUID())
                .id(chId).semantic(ChannelSemantic.APPEND).tenancyId(TENANT_A).build());

        Mockito.when(currentPrincipal.tenancyId()).thenReturn(TENANT_B);
        Optional<Channel> found = channelStore.find(ch.id());
        var               list  = channelStore.scan(ChannelQuery.builder().build());

        assertThat(found).isEmpty();
        assertThat(list).noneMatch(c -> c.id().equals(ch.id()));
    }

    @Test
    @Transactional
    void channel_findByName_scopedToTenant() {
        String name = "shared-name-" + UUID.randomUUID();

        Mockito.when(currentPrincipal.tenancyId()).thenReturn(TENANT_A);
        Channel chA = channelStore.put(Channel.builder(name)
                .id(UUID.randomUUID()).semantic(ChannelSemantic.APPEND).tenancyId(TENANT_A).build());

        Mockito.when(currentPrincipal.tenancyId()).thenReturn(TENANT_B);
        Channel chB = channelStore.put(Channel.builder(name)
                .id(UUID.randomUUID()).semantic(ChannelSemantic.APPEND).tenancyId(TENANT_B).build());

        Mockito.when(currentPrincipal.tenancyId()).thenReturn(TENANT_A);
        Optional<Channel> foundA = channelStore.findByName(name);
        assertThat(foundA).isPresent().get().extracting(Channel::id).isEqualTo(chA.id());

        Mockito.when(currentPrincipal.tenancyId()).thenReturn(TENANT_B);
        Optional<Channel> foundB = channelStore.findByName(name);
        assertThat(foundB).isPresent().get().extracting(Channel::id).isEqualTo(chB.id());
    }

    @Test
    @Transactional
    void message_createdInTenantA_notVisibleToTenantB() {
        Mockito.when(currentPrincipal.tenancyId()).thenReturn(TENANT_A);
        Channel ch = channelStore.put(Channel.builder("msg-isolation-" + UUID.randomUUID())
                .id(UUID.randomUUID()).semantic(ChannelSemantic.APPEND).tenancyId(TENANT_A).build());

        messageStore.put(Message.builder().channelId(ch.id()).sender("agent-a")
                .messageType(MessageType.QUERY).actorType(ActorType.AGENT)
                .tenancyId(TENANT_A).build());

        Mockito.when(currentPrincipal.tenancyId()).thenReturn(TENANT_B);
        var list = messageStore.scan(MessageQuery.builder().channelId(ch.id()).build());
        assertThat(list).isEmpty();
        int count = messageStore.countByChannel(ch.id());
        assertThat(count).isZero();
    }

    @Test
    @Transactional
    void commitment_createdInTenantA_notVisibleToTenantB() {
        Mockito.when(currentPrincipal.tenancyId()).thenReturn(TENANT_A);
        UUID commitmentId = UUID.randomUUID();
        Commitment c = commitmentStore.save(Commitment.builder()
                .id(commitmentId)
                .correlationId("corr-" + UUID.randomUUID())
                .channelId(UUID.randomUUID())
                .obligor("agent-a").requester("agent-req")
                .state(CommitmentState.OPEN)
                .messageType(MessageType.COMMAND)
                .tenancyId(TENANT_A).build());

        Mockito.when(currentPrincipal.tenancyId()).thenReturn(TENANT_B);
        var open = commitmentStore.findAllOpen();
        assertThat(open).noneMatch(x -> x.id().equals(c.id()));
    }
}
