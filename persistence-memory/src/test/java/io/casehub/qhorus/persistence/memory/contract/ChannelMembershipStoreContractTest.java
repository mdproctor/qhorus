package io.casehub.qhorus.persistence.memory.contract;

import io.casehub.qhorus.api.channel.ChannelMembership;
import io.casehub.qhorus.api.channel.MemberRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

abstract class ChannelMembershipStoreContractTest {

    protected abstract ChannelMembership put(ChannelMembership m);
    protected abstract java.util.Optional<ChannelMembership> find(UUID channelId, String memberId);
    protected abstract java.util.List<ChannelMembership> findByChannel(UUID channelId);
    protected abstract java.util.List<ChannelMembership> findByMember(String memberId, String tenancyId);
    protected abstract void updateRole(UUID channelId, String memberId, MemberRole role);
    protected abstract void updateLastReadMessageId(UUID channelId, String memberId, Long messageId);

    protected abstract void updateLastDeliveredMessageId(UUID channelId, String memberId, Long messageId);

    protected abstract void advanceDeliveredCursorForMembers(UUID channelId, java.util.Set<String> memberIds, Long messageId);

    protected abstract boolean delete(UUID channelId, String memberId);
    protected abstract void deleteAll(UUID channelId);

    private UUID channelId;

    @BeforeEach
    void setUp() {
        channelId = UUID.randomUUID();
    }

    @Test
    void putAndFind() {
        var m = new ChannelMembership(null, channelId, "agent-1", MemberRole.PARTICIPANT, "default", Instant.now(), null);
        var saved = put(m);
        assertThat(saved.id()).isNotNull();
        var found = find(channelId, "agent-1");
        assertThat(found).isPresent();
        assertThat(found.get().role()).isEqualTo(MemberRole.PARTICIPANT);
    }

    @Test
    void findByChannel_returnsAll() {
        put(new ChannelMembership(null, channelId, "agent-1", MemberRole.PARTICIPANT, "default", Instant.now(), null));
        put(new ChannelMembership(null, channelId, "agent-2", MemberRole.OBSERVER, "default", Instant.now(), null));
        assertThat(findByChannel(channelId)).hasSize(2);
    }

    @Test
    void findByMember_returnsAcrossChannels() {
        UUID ch2 = UUID.randomUUID();
        put(new ChannelMembership(null, channelId, "agent-1", MemberRole.PARTICIPANT, "default", Instant.now(), null));
        put(new ChannelMembership(null, ch2, "agent-1", MemberRole.OBSERVER, "default", Instant.now(), null));
        assertThat(findByMember("agent-1", "default")).hasSize(2);
    }

    @Test
    void updateRole() {
        put(new ChannelMembership(null, channelId, "agent-1", MemberRole.PARTICIPANT, "default", Instant.now(), null));
        updateRole(channelId, "agent-1", MemberRole.MODERATOR);
        assertThat(find(channelId, "agent-1").get().role()).isEqualTo(MemberRole.MODERATOR);
    }

    @Test
    void updateLastReadMessageId() {
        put(new ChannelMembership(null, channelId, "agent-1", MemberRole.PARTICIPANT, "default", Instant.now(), 0L));
        updateLastReadMessageId(channelId, "agent-1", 42L);
        assertThat(find(channelId, "agent-1").get().lastReadMessageId()).isEqualTo(42L);
    }

    @Test
    void delete_existing() {
        put(new ChannelMembership(null, channelId, "agent-1", MemberRole.PARTICIPANT, "default", Instant.now(), null));
        assertThat(delete(channelId, "agent-1")).isTrue();
        assertThat(find(channelId, "agent-1")).isEmpty();
    }

    @Test
    void delete_nonExistent() {
        assertThat(delete(channelId, "nobody")).isFalse();
    }

    @Test
    void deleteAll() {
        put(new ChannelMembership(null, channelId, "agent-1", MemberRole.PARTICIPANT, "default", Instant.now(), null));
        put(new ChannelMembership(null, channelId, "agent-2", MemberRole.OBSERVER, "default", Instant.now(), null));
        deleteAll(channelId);
        assertThat(findByChannel(channelId)).isEmpty();
    }

    @Test
    void updateLastDeliveredMessageId_advancesForward() {
        put(new ChannelMembership(null, channelId, "agent-a", MemberRole.PARTICIPANT, "default", Instant.now(), null));
        updateLastDeliveredMessageId(channelId, "agent-a", 10L);
        assertThat(find(channelId, "agent-a").orElseThrow().lastDeliveredMessageId()).isEqualTo(10L);
    }

    @Test
    void updateLastDeliveredMessageId_forwardOnly_lowerIdIsNoOp() {
        put(new ChannelMembership(null, channelId, "agent-a", MemberRole.PARTICIPANT, "default", Instant.now(), null));
        updateLastDeliveredMessageId(channelId, "agent-a", 10L);
        updateLastDeliveredMessageId(channelId, "agent-a", 5L);
        assertThat(find(channelId, "agent-a").orElseThrow().lastDeliveredMessageId()).isEqualTo(10L);
    }

    @Test
    void updateLastDeliveredMessageId_nullToFirstValue() {
        put(new ChannelMembership(null, channelId, "agent-a", MemberRole.PARTICIPANT, "default", Instant.now(), null));
        assertThat(find(channelId, "agent-a").orElseThrow().lastDeliveredMessageId()).isNull();
        updateLastDeliveredMessageId(channelId, "agent-a", 1L);
        assertThat(find(channelId, "agent-a").orElseThrow().lastDeliveredMessageId()).isEqualTo(1L);
    }

    @Test
    void updateLastDeliveredMessageId_idempotent() {
        put(new ChannelMembership(null, channelId, "agent-a", MemberRole.PARTICIPANT, "default", Instant.now(), null));
        updateLastDeliveredMessageId(channelId, "agent-a", 10L);
        updateLastDeliveredMessageId(channelId, "agent-a", 10L);
        assertThat(find(channelId, "agent-a").orElseThrow().lastDeliveredMessageId()).isEqualTo(10L);
    }

    @Test
    void advanceDeliveredCursorForMembers_advancesSpecifiedMembers() {
        put(new ChannelMembership(null, channelId, "agent-a", MemberRole.PARTICIPANT, "default", Instant.now(), null));
        put(new ChannelMembership(null, channelId, "agent-b", MemberRole.PARTICIPANT, "default", Instant.now(), null));
        put(new ChannelMembership(null, channelId, "agent-c", MemberRole.PARTICIPANT, "default", Instant.now(), null));
        advanceDeliveredCursorForMembers(channelId, java.util.Set.of("agent-a", "agent-b"), 10L);
        assertThat(find(channelId, "agent-a").orElseThrow().lastDeliveredMessageId()).isEqualTo(10L);
        assertThat(find(channelId, "agent-b").orElseThrow().lastDeliveredMessageId()).isEqualTo(10L);
        assertThat(find(channelId, "agent-c").orElseThrow().lastDeliveredMessageId()).isNull();
    }

    @Test
    void advanceDeliveredCursorForMembers_forwardOnly() {
        put(new ChannelMembership(null, channelId, "agent-a", MemberRole.PARTICIPANT, "default", Instant.now(), null));
        advanceDeliveredCursorForMembers(channelId, java.util.Set.of("agent-a"), 10L);
        advanceDeliveredCursorForMembers(channelId, java.util.Set.of("agent-a"), 5L);
        assertThat(find(channelId, "agent-a").orElseThrow().lastDeliveredMessageId()).isEqualTo(10L);
    }

    @Test
    void updateLastDeliveredMessageId_preservesLastReadMessageId() {
        put(new ChannelMembership(null, channelId, "agent-a", MemberRole.PARTICIPANT, "default", Instant.now(), 42L));
        updateLastDeliveredMessageId(channelId, "agent-a", 10L);
        var m = find(channelId, "agent-a").orElseThrow();
        assertThat(m.lastDeliveredMessageId()).isEqualTo(10L);
        assertThat(m.lastReadMessageId()).isEqualTo(42L);
    }

}
