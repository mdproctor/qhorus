package io.casehub.qhorus.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.casehub.platform.api.identity.TenancyConstants;
import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.casehub.qhorus.runtime.channel.Channel;
import io.casehub.qhorus.runtime.store.ChannelStore;
import io.casehub.qhorus.runtime.store.query.ChannelQuery;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class JpaChannelStoreTest {

    @Inject
    ChannelStore channelStore;

    /** Builds a channel with tenancyId matching the MockCurrentPrincipal default. */
    private Channel buildChannel(String name, ChannelSemantic semantic) {
        Channel ch = new Channel();
        ch.name = name;
        ch.semantic = semantic;
        ch.tenancyId = TenancyConstants.DEFAULT_TENANT_ID;
        return ch;
    }

    @Test
    @TestTransaction
    void put_persistsChannelAndAssignsId() {
        Channel ch = buildChannel("put-test-" + UUID.randomUUID(), ChannelSemantic.APPEND);

        Channel saved = channelStore.put(ch);

        assertNotNull(saved.id);
        assertEquals(ch.name, saved.name);
    }

    @Test
    @TestTransaction
    void find_returnsChannel_whenExists() {
        Channel ch = buildChannel("find-test-" + UUID.randomUUID(), ChannelSemantic.APPEND);
        channelStore.put(ch);

        Optional<Channel> found = channelStore.find(ch.id);

        assertTrue(found.isPresent());
        assertEquals(ch.name, found.get().name);
    }

    @Test
    @TestTransaction
    void find_returnsEmpty_whenNotFound() {
        assertTrue(channelStore.find(UUID.randomUUID()).isEmpty());
    }

    @Test
    @TestTransaction
    void findByName_returnsChannel_whenExists() {
        Channel ch = buildChannel("named-" + UUID.randomUUID(), ChannelSemantic.COLLECT);
        channelStore.put(ch);

        Optional<Channel> found = channelStore.findByName(ch.name);

        assertTrue(found.isPresent());
        assertEquals(ChannelSemantic.COLLECT, found.get().semantic);
    }

    @Test
    @TestTransaction
    void scan_pausedOnly_returnsOnlyPausedChannels() {
        String suffix = UUID.randomUUID().toString();

        Channel active = buildChannel("active-" + suffix, ChannelSemantic.APPEND);
        active.paused = false;
        channelStore.put(active);

        Channel paused = buildChannel("paused-" + suffix, ChannelSemantic.APPEND);
        paused.paused = true;
        channelStore.put(paused);

        // Note: factory method is pausedOnly() not paused() — naming collision with accessor
        List<Channel> results = channelStore.scan(ChannelQuery.pausedOnly());

        assertTrue(results.stream().anyMatch(c -> c.name.equals(paused.name)));
        assertTrue(results.stream().noneMatch(c -> c.name.equals(active.name)));
    }

    @Test
    @TestTransaction
    void scan_byNamePrefix_returnsMatchingChannels() {
        String prefix = "pfx-" + UUID.randomUUID() + "/";

        Channel work = buildChannel(prefix + "work", ChannelSemantic.APPEND);
        channelStore.put(work);

        Channel observe = buildChannel(prefix + "observe", ChannelSemantic.APPEND);
        channelStore.put(observe);

        Channel other = buildChannel("other-" + UUID.randomUUID(), ChannelSemantic.APPEND);
        channelStore.put(other);

        List<Channel> results = channelStore.scan(ChannelQuery.byNamePrefix(prefix));

        assertEquals(2, results.size());
        assertTrue(results.stream().allMatch(c -> c.name.startsWith(prefix)));
    }

    @Test
    @TestTransaction
    void scan_byNamePrefix_doesNotMatchChannelWithUnderscore_inPrefix() {
        String suffix = UUID.randomUUID().toString();
        // Channel whose name differs from the prefix only because _ would be a SQL wildcard
        Channel withDash = buildChannel("case-" + suffix, ChannelSemantic.APPEND);
        channelStore.put(withDash);

        Channel withUnderscore = buildChannel("case_" + suffix, ChannelSemantic.APPEND);
        channelStore.put(withUnderscore);

        // Search for prefix "case_<suffix>" — should not match "case-<suffix>" via SQL wildcard
        List<Channel> results = channelStore.scan(ChannelQuery.byNamePrefix("case_" + suffix));

        assertEquals(1, results.size());
        assertEquals(withUnderscore.name, results.get(0).name);
    }

    @Test
    @TestTransaction
    void delete_removesChannel() {
        Channel ch = buildChannel("delete-test-" + UUID.randomUUID(), ChannelSemantic.APPEND);
        channelStore.put(ch);

        channelStore.delete(ch.id);

        assertTrue(channelStore.find(ch.id).isEmpty());
    }

    @Test
    @TestTransaction
    void updateLastActivity_setsLastActivityAt() {
        Channel ch = buildChannel("activity-test-" + UUID.randomUUID(), ChannelSemantic.APPEND);
        channelStore.put(ch);

        Instant before = Instant.now();
        channelStore.updateLastActivity(ch.id, TenancyConstants.DEFAULT_TENANT_ID);
        // JPQL bulk UPDATE bypasses Hibernate L1 cache — clear to force re-read from DB.
        Channel.getEntityManager().clear();

        Channel found = channelStore.find(ch.id).orElseThrow();
        assertThat(found.lastActivityAt).isAfterOrEqualTo(before);
    }
}
