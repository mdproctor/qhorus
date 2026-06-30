package io.casehub.qhorus.persistence.memory.contract;

import static org.junit.jupiter.api.Assertions.*;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.casehub.platform.api.identity.TenancyConstants;
import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.casehub.qhorus.runtime.channel.Channel;
import io.casehub.qhorus.runtime.store.query.ChannelQuery;

public abstract class ChannelStoreContractTest {

    protected abstract Channel put(Channel channel);

    protected abstract Optional<Channel> find(UUID id);

    protected abstract Optional<Channel> findByName(String name);

    protected abstract List<Channel> scan(ChannelQuery query);

    protected abstract void delete(UUID id);

    protected abstract void updateLastActivity(UUID channelId, String tenancyId);

    protected abstract List<Channel> findByIds(Collection<UUID> ids);

    protected abstract void reset();

    @BeforeEach
    void beforeEach() {
        reset();
    }

    @Test
    void put_assignsId_whenNull() {
        Channel ch = channel("put-null-" + UUID.randomUUID(), ChannelSemantic.APPEND);
        assertNotNull(put(ch).id);
    }

    @Test
    void put_preservesExistingId() {
        Channel ch = channel("put-preset-" + UUID.randomUUID(), ChannelSemantic.COLLECT);
        ch.id = UUID.randomUUID();
        UUID expected = ch.id;
        assertEquals(expected, put(ch).id);
    }

    @Test
    void find_returnsChannel_whenPresent() {
        Channel ch = channel("find-present-" + UUID.randomUUID(), ChannelSemantic.APPEND);
        put(ch);
        assertTrue(find(ch.id).isPresent());
    }

    @Test
    void find_returnsEmpty_whenAbsent() {
        assertTrue(find(UUID.randomUUID()).isEmpty());
    }

    @Test
    void findByName_returnsChannel_whenExists() {
        String name = "findname-" + UUID.randomUUID();
        Channel ch = channel(name, ChannelSemantic.BARRIER);
        put(ch);
        Optional<Channel> found = findByName(name);
        assertTrue(found.isPresent());
        assertEquals(ChannelSemantic.BARRIER, found.get().semantic);
    }

    @Test
    void findByName_returnsEmpty_whenNoMatch() {
        assertTrue(findByName("nosuch-" + UUID.randomUUID()).isEmpty());
    }

    @Test
    void scan_all_returnsAllPutChannels() {
        put(channel("scan-a-" + UUID.randomUUID(), ChannelSemantic.APPEND));
        put(channel("scan-b-" + UUID.randomUUID(), ChannelSemantic.COLLECT));
        assertTrue(scan(ChannelQuery.all()).size() >= 2);
    }

    @Test
    void scan_pausedOnly_returnsOnlyPaused() {
        Channel active = channel("active-" + UUID.randomUUID(), ChannelSemantic.APPEND);
        active.paused = false;
        put(active);
        Channel paused = channel("paused-" + UUID.randomUUID(), ChannelSemantic.APPEND);
        paused.paused = true;
        put(paused);
        List<Channel> results = scan(ChannelQuery.pausedOnly());
        assertTrue(results.stream().anyMatch(c -> c.name.equals(paused.name)));
        assertTrue(results.stream().noneMatch(c -> c.name.equals(active.name)));
    }

    @Test
    void delete_removesChannel() {
        Channel ch = channel("del-" + UUID.randomUUID(), ChannelSemantic.APPEND);
        put(ch);
        delete(ch.id);
        assertTrue(find(ch.id).isEmpty());
    }

    @Test
    void put_and_find_preserves_allowedTypes() {
        Channel ch = channel("allowed-types-" + UUID.randomUUID(), ChannelSemantic.APPEND);
        ch.allowedTypes = "EVENT";
        put(ch);
        Channel found = find(ch.id).orElseThrow();
        assertEquals("EVENT", found.allowedTypes);
    }

    @Test
    void put_and_find_preserves_null_allowedTypes() {
        Channel ch = channel("null-allowed-" + UUID.randomUUID(), ChannelSemantic.APPEND);
        ch.allowedTypes = null;
        put(ch);
        Channel found = find(ch.id).orElseThrow();
        assertNull(found.allowedTypes);
    }

    @Test
    void scan_byNamePrefix_returnsMatchingChannels() {
        String prefix = "pfx-" + UUID.randomUUID() + "/";
        put(channel(prefix + "work", ChannelSemantic.APPEND));
        put(channel(prefix + "observe", ChannelSemantic.APPEND));
        put(channel("other-" + UUID.randomUUID(), ChannelSemantic.APPEND));

        List<Channel> results = scan(ChannelQuery.byNamePrefix(prefix));

        assertEquals(2, results.size());
        assertTrue(results.stream().allMatch(c -> c.name.startsWith(prefix)));
    }

    @Test
    void scan_byNamePrefix_returnsEmpty_whenNoMatch() {
        put(channel("other-" + UUID.randomUUID(), ChannelSemantic.APPEND));
        List<Channel> results = scan(ChannelQuery.byNamePrefix("nomatch-" + UUID.randomUUID()));
        assertTrue(results.isEmpty());
    }

    @Test
    void scan_byNamePrefix_doesNotReturn_channelWithSupersetName() {
        put(channel("ab-" + UUID.randomUUID(), ChannelSemantic.APPEND));
        List<Channel> results = scan(ChannelQuery.byNamePrefix("abc-"));
        assertTrue(results.isEmpty());
    }

    @Test
    void updateLastActivity_setsTimestamp() {
        Channel ch = channel("act-test-" + UUID.randomUUID(), ChannelSemantic.APPEND);
        put(ch);
        updateLastActivity(ch.id, TenancyConstants.DEFAULT_TENANT_ID);
        Optional<Channel> found = find(ch.id);
        assertTrue(found.isPresent());
        assertNotNull(found.get().lastActivityAt);
    }

    @Test
    void findByIds_allPresent() {
        Channel ch1 = put(channel("findByIds-1-" + UUID.randomUUID(), ChannelSemantic.APPEND));
        Channel ch2 = put(channel("findByIds-2-" + UUID.randomUUID(), ChannelSemantic.COLLECT));
        List<Channel> result = findByIds(List.of(ch1.id, ch2.id));
        assertThat(result).hasSize(2);
        assertThat(result).extracting(c -> c.id).containsExactlyInAnyOrder(ch1.id, ch2.id);
    }

    @Test
    void findByIds_partiallyPresent_missingIdsOmitted() {
        Channel ch = put(channel("findByIds-partial-" + UUID.randomUUID(), ChannelSemantic.APPEND));
        List<Channel> result = findByIds(List.of(ch.id, UUID.randomUUID()));
        assertThat(result).hasSize(1);
        assertEquals(ch.id, result.get(0).id);
    }

    @Test
    void findByIds_emptyCollection_returnsEmpty() {
        assertThat(findByIds(List.of())).isEmpty();
    }

    @Test
    void findByIds_unknownIds_returnsEmpty() {
        assertThat(findByIds(List.of(UUID.randomUUID(), UUID.randomUUID()))).isEmpty();
    }

    protected Channel channel(String name, ChannelSemantic semantic) {
        Channel ch = new Channel();
        ch.name = name;
        ch.semantic = semantic;
        return ch;
    }
}
