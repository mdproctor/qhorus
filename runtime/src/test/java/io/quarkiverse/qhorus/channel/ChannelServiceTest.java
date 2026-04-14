package io.quarkiverse.qhorus.channel;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.quarkiverse.qhorus.runtime.channel.Channel;
import io.quarkiverse.qhorus.runtime.channel.ChannelSemantic;
import io.quarkiverse.qhorus.runtime.channel.ChannelService;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class ChannelServiceTest {

    @Inject
    ChannelService channelService;

    @Test
    @TestTransaction
    void createChannelPersistsAllFields() {
        Channel ch = channelService.create("auth-refactor", "Auth refactoring thread", ChannelSemantic.APPEND, null);

        assertNotNull(ch.id);
        assertEquals("auth-refactor", ch.name);
        assertEquals("Auth refactoring thread", ch.description);
        assertEquals(ChannelSemantic.APPEND, ch.semantic);
        assertNull(ch.barrierContributors);
        assertNotNull(ch.createdAt);
        assertNotNull(ch.lastActivityAt);
    }

    @Test
    @TestTransaction
    void createChannelWithBarrierContributors() {
        Channel ch = channelService.create("sync-point", "Wait for all reviewers", ChannelSemantic.BARRIER, "alice,bob,carol");

        assertEquals(ChannelSemantic.BARRIER, ch.semantic);
        assertEquals("alice,bob,carol", ch.barrierContributors);
    }

    @Test
    @TestTransaction
    void findByNameReturnsChannel() {
        channelService.create("findings", "Research findings", ChannelSemantic.COLLECT, null);

        Optional<Channel> found = channelService.findByName("findings");

        assertTrue(found.isPresent());
        assertEquals("findings", found.get().name);
        assertEquals(ChannelSemantic.COLLECT, found.get().semantic);
    }

    @Test
    @TestTransaction
    void findByNameReturnsEmptyWhenNotFound() {
        Optional<Channel> found = channelService.findByName("no-such-channel");

        assertTrue(found.isEmpty());
    }

    @Test
    @TestTransaction
    void listAllReturnsCreatedChannels() {
        channelService.create("alpha", "Alpha channel", ChannelSemantic.APPEND, null);
        channelService.create("beta", "Beta channel", ChannelSemantic.LAST_WRITE, null);

        List<Channel> channels = channelService.listAll();

        assertTrue(channels.size() >= 2);
        assertTrue(channels.stream().anyMatch(c -> "alpha".equals(c.name)));
        assertTrue(channels.stream().anyMatch(c -> "beta".equals(c.name)));
    }

    @Test
    @TestTransaction
    void updateLastActivityAdvancesTimestamp() throws InterruptedException {
        Channel ch = channelService.create("active-ch", "Active channel", ChannelSemantic.APPEND, null);
        Instant original = ch.lastActivityAt;

        // ensure at least 1ms passes
        Thread.sleep(5);
        channelService.updateLastActivity(ch.id);

        Channel updated = channelService.findByName("active-ch").orElseThrow();
        assertTrue(updated.lastActivityAt.isAfter(original),
                "lastActivityAt should advance after updateLastActivity");
    }
}
