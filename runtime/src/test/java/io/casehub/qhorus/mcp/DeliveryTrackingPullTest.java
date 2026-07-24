package io.casehub.qhorus.mcp;

import io.casehub.qhorus.api.channel.ChannelCreateRequest;
import io.casehub.qhorus.api.channel.ChannelMembership;
import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.casehub.qhorus.api.channel.MemberRole;
import io.casehub.qhorus.api.store.ChannelMembershipStore;
import io.casehub.qhorus.runtime.channel.ChannelService;
import io.casehub.qhorus.runtime.mcp.QhorusMcpTools;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import io.quarkus.test.TestTransaction;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class DeliveryTrackingPullTest {

    @Inject QhorusMcpTools tools;
    @Inject ChannelMembershipStore membershipStore;
    @Inject ChannelService channelService;

    @Test
    @TestTransaction
    void checkMessages_barrierChannel_advancesDeliveryCursor() {
        String suffix = Long.toHexString(System.nanoTime());
        var ch = channelService.create(ChannelCreateRequest.builder("barrier-dt-" + suffix)
                .semantic(ChannelSemantic.BARRIER)
                .barrierContributors(List.of("agent-a", "agent-b"))
                .build());
        String readerId = "reader-" + suffix;
        tools.register(readerId, "test reader", null, null, null);
        membershipStore.put(new ChannelMembership(null, ch.id(), readerId,
                MemberRole.PARTICIPANT, null, Instant.now(), null));
        tools.sendMessage(ch.name(), "agent-a", "STATUS", "contribution-a",
                null, null, null, null, null, null, null, null);
        tools.sendMessage(ch.name(), "agent-b", "STATUS", "contribution-b",
                null, null, null, null, null, null, null, null);
        tools.checkMessages(ch.name(), 0L, null, null, readerId, null);
        var membership = membershipStore.find(ch.id(), readerId).orElseThrow();
        assertNotNull(membership.lastDeliveredMessageId());
        assertTrue(membership.lastDeliveredMessageId() > 0);
    }

    @Test
    @TestTransaction
    void checkMessages_appendChannel_doesNotAdvanceCursor() {
        String suffix = Long.toHexString(System.nanoTime());
        var ch = channelService.create(ChannelCreateRequest.builder("append-dt-" + suffix)
                .semantic(ChannelSemantic.APPEND).build());
        String readerId = "reader-" + suffix;
        tools.register(readerId, "test reader", null, null, null);
        membershipStore.put(new ChannelMembership(null, ch.id(), readerId,
                MemberRole.PARTICIPANT, null, Instant.now(), null));
        tools.sendMessage(ch.name(), "agent-a", "STATUS", "hello",
                null, null, null, null, null, null, null, null);
        tools.checkMessages(ch.name(), 0L, null, null, readerId, null);
        var membership = membershipStore.find(ch.id(), readerId).orElseThrow();
        assertNull(membership.lastDeliveredMessageId());
    }

    @Test
    @TestTransaction
    void checkMessages_noReaderInstanceId_doesNotAdvance() {
        String suffix = Long.toHexString(System.nanoTime());
        var ch = channelService.create(ChannelCreateRequest.builder("barrier-nr-" + suffix)
                .semantic(ChannelSemantic.BARRIER)
                .barrierContributors(List.of("agent-a"))
                .build());
        tools.sendMessage(ch.name(), "agent-a", "STATUS", "data",
                null, null, null, null, null, null, null, null);
        var result = tools.checkMessages(ch.name(), 0L, null, null, null, null);
        assertNotNull(result);
    }

    @Test
    @TestTransaction
    void checkMessages_appendWithExplicitTrackDelivery_advancesCursor() {
        String suffix = Long.toHexString(System.nanoTime());
        var ch = channelService.create(ChannelCreateRequest.builder("append-edt-" + suffix)
                .semantic(ChannelSemantic.APPEND)
                .trackDelivery(true)
                .build());
        String readerId = "reader-" + suffix;
        tools.register(readerId, "test reader", null, null, null);
        membershipStore.put(new ChannelMembership(null, ch.id(), readerId,
                MemberRole.PARTICIPANT, null, Instant.now(), null));
        tools.sendMessage(ch.name(), "agent-a", "STATUS", "tracked",
                null, null, null, null, null, null, null, null);
        tools.checkMessages(ch.name(), 0L, null, null, readerId, null);
        var membership = membershipStore.find(ch.id(), readerId).orElseThrow();
        assertNotNull(membership.lastDeliveredMessageId());
        assertTrue(membership.lastDeliveredMessageId() > 0);
    }
}
