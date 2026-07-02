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
import io.casehub.qhorus.api.channel.Channel;
import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.casehub.qhorus.runtime.channel.ChannelEntity;
import io.casehub.qhorus.api.store.ChannelStore;
import io.casehub.qhorus.api.store.query.ChannelQuery;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class JpaChannelStoreTest {

    @Inject
    ChannelStore channelStore;

    private Channel buildChannel(String name, ChannelSemantic semantic) {
        return Channel.builder(name).semantic(semantic)
                .tenancyId(TenancyConstants.DEFAULT_TENANT_ID).build();
    }

    @Test
    @TestTransaction
    void put_persistsChannelAndAssignsId() {
        Channel ch = buildChannel("put-test-" + UUID.randomUUID(), ChannelSemantic.APPEND);

        Channel saved = channelStore.put(ch);

        assertNotNull(saved.id());
        assertEquals(ch.name(), saved.name());
    }

    @Test
    @TestTransaction
    void find_returnsChannel_whenExists() {
        Channel ch = channelStore.put(buildChannel("find-test-" + UUID.randomUUID(), ChannelSemantic.APPEND));

        Optional<Channel> found = channelStore.find(ch.id());

        assertTrue(found.isPresent());
        assertEquals(ch.name(), found.get().name());
    }

    @Test
    @TestTransaction
    void find_returnsEmpty_whenNotFound() {
        assertTrue(channelStore.find(UUID.randomUUID()).isEmpty());
    }

    @Test
    @TestTransaction
    void findByName_returnsChannel_whenExists() {
        Channel ch = channelStore.put(buildChannel("named-" + UUID.randomUUID(), ChannelSemantic.COLLECT));

        Optional<Channel> found = channelStore.findByName(ch.name());

        assertTrue(found.isPresent());
        assertEquals(ChannelSemantic.COLLECT, found.get().semantic());
    }

    @Test
    @TestTransaction
    void scan_pausedOnly_returnsOnlyPausedChannels() {
        String suffix = UUID.randomUUID().toString();

        Channel active = channelStore.put(buildChannel("active-" + suffix, ChannelSemantic.APPEND));

        Channel paused = channelStore.put(buildChannel("paused-" + suffix, ChannelSemantic.APPEND)
                .toBuilder().paused(true).build());

        List<Channel> results = channelStore.scan(ChannelQuery.pausedOnly());

        assertTrue(results.stream().anyMatch(c -> c.name().equals(paused.name())));
        assertTrue(results.stream().noneMatch(c -> c.name().equals(active.name())));
    }

    @Test
    @TestTransaction
    void scan_byNamePrefix_returnsMatchingChannels() {
        String prefix = "pfx-" + UUID.randomUUID() + "/";

        channelStore.put(buildChannel(prefix + "work", ChannelSemantic.APPEND));
        channelStore.put(buildChannel(prefix + "observe", ChannelSemantic.APPEND));
        channelStore.put(buildChannel("other-" + UUID.randomUUID(), ChannelSemantic.APPEND));

        List<Channel> results = channelStore.scan(ChannelQuery.byNamePrefix(prefix));

        assertEquals(2, results.size());
        assertTrue(results.stream().allMatch(c -> c.name().startsWith(prefix)));
    }

    @Test
    @TestTransaction
    void scan_byNamePrefix_doesNotMatchChannelWithUnderscore_inPrefix() {
        String suffix = UUID.randomUUID().toString();

        channelStore.put(buildChannel("case-" + suffix, ChannelSemantic.APPEND));
        Channel withUnderscore = channelStore.put(buildChannel("case_" + suffix, ChannelSemantic.APPEND));

        List<Channel> results = channelStore.scan(ChannelQuery.byNamePrefix("case_" + suffix));

        assertEquals(1, results.size());
        assertEquals(withUnderscore.name(), results.get(0).name());
    }

    @Test
    @TestTransaction
    void delete_removesChannel() {
        Channel ch = channelStore.put(buildChannel("delete-test-" + UUID.randomUUID(), ChannelSemantic.APPEND));

        channelStore.delete(ch.id());

        assertTrue(channelStore.find(ch.id()).isEmpty());
    }

    @Test
    @TestTransaction
    void updateLastActivity_setsLastActivityAt() {
        Channel ch = channelStore.put(buildChannel("activity-test-" + UUID.randomUUID(), ChannelSemantic.APPEND));

        Instant before = Instant.now();
        channelStore.updateLastActivity(ch.id(), TenancyConstants.DEFAULT_TENANT_ID);
        ChannelEntity.getEntityManager().clear();

        Channel found = channelStore.find(ch.id()).orElseThrow();
        assertThat(found.lastActivityAt()).isAfterOrEqualTo(before);
    }
}
