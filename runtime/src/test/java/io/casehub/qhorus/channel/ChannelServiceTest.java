package io.casehub.qhorus.channel;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.casehub.platform.api.identity.ActorTypeResolver;
import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.casehub.qhorus.api.message.MessageDispatch;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.channel.Channel;
import io.casehub.qhorus.runtime.channel.ChannelService;
import io.casehub.qhorus.runtime.message.MessageService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class ChannelServiceTest {

    @Inject
    ChannelService channelService;

    @Inject
    MessageService messageService;

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

    @Test
    void createWithAllowedTypes_storesConstraint() {
        String name = "allowed-types-" + System.nanoTime();
        QuarkusTransaction.requiringNew().run(() -> {
            Channel ch = channelService.create(name, "Telemetry", ChannelSemantic.APPEND,
                    null, null, null, null, null, "EVENT");
            assertEquals("EVENT", ch.allowedTypes);
        });
    }

    @Test
    void createWithNullAllowedTypes_storesNull() {
        String name = "no-allowed-types-" + System.nanoTime();
        QuarkusTransaction.requiringNew().run(() -> {
            Channel ch = channelService.create(name, "Open", ChannelSemantic.APPEND,
                    null, null, null, null, null, null);
            assertNull(ch.allowedTypes);
        });
    }

    @Test
    void createWithBlankAllowedTypes_storesNull() {
        String name = "blank-allowed-types-" + System.nanoTime();
        QuarkusTransaction.requiringNew().run(() -> {
            Channel ch = channelService.create(name, "Open", ChannelSemantic.APPEND,
                    null, null, null, null, null, "  ");
            assertNull(ch.allowedTypes);
        });
    }

    @Test
    void existingFourParamOverload_setsNullAllowedTypes() {
        String name = "legacy-overload-" + System.nanoTime();
        QuarkusTransaction.requiringNew().run(() -> {
            Channel ch = channelService.create(name, "Legacy", ChannelSemantic.APPEND, null);
            assertNull(ch.allowedTypes);
        });
    }

    // ------------------------------------------------------------------
    // denied types — creation and dispatch enforcement
    // ------------------------------------------------------------------

    @Test
    void createWithDeniedTypes_storesDeniedTypes() {
        String name = "denied-types-" + System.nanoTime();
        QuarkusTransaction.requiringNew().run(() -> {
            Channel ch = channelService.create(name, "Oversight", ChannelSemantic.APPEND,
                    null, null, null, null, null, null, "EVENT");
            assertEquals("EVENT", ch.deniedTypes);
            assertNull(ch.allowedTypes);
        });
        QuarkusTransaction.requiringNew().run(() -> Channel.delete("name", name));
    }

    @Test
    void createWithOverlappingTypes_throwsAtCreation() {
        assertThrows(IllegalArgumentException.class, () ->
                QuarkusTransaction.requiringNew().run(() ->
                        channelService.create("overlap-" + System.nanoTime(), "Bad", ChannelSemantic.APPEND,
                                null, null, null, null, null, "QUERY", "QUERY")));
    }

    @Test
    void dispatch_deniedType_throwsViolation() throws InterruptedException {
        String name = "denied-event-" + System.nanoTime();
        QuarkusTransaction.requiringNew().run(() ->
                channelService.create(name, "Oversight", ChannelSemantic.APPEND,
                        null, null, null, null, null, null, "EVENT"));
        UUID[] chId = new UUID[1];
        QuarkusTransaction.requiringNew().run(() -> chId[0] = channelService.findByName(name).orElseThrow().id);

        assertThrows(io.casehub.qhorus.api.message.MessageTypeViolationException.class, () ->
                QuarkusTransaction.requiringNew().run(() ->
                        messageService.dispatch(MessageDispatch.builder()
                                .channelId(chId[0])
                                .sender("telemetry-agent")
                                .type(MessageType.EVENT)
                                .telemetry("{\"tool\":\"search\"}")
                                .actorType(ActorTypeResolver.resolve("telemetry-agent"))
                                .build())));

        // non-denied type passes
        QuarkusTransaction.requiringNew().run(() ->
                messageService.dispatch(MessageDispatch.builder()
                        .channelId(chId[0])
                        .sender("overseer")
                        .type(MessageType.COMMAND)
                        .content("proceed")
                        .actorType(ActorTypeResolver.resolve("overseer"))
                        .build()));

        QuarkusTransaction.requiringNew().run(() -> Channel.delete("name", name));
    }

    @Test
    void duplicateChannelNameThrowsException() {
        // Use explicit transactions so each commit is independent and the unique
        // constraint is actually checked against the DB (not just the Hibernate cache)
        String uniqueName = "dup-test-" + System.nanoTime();
        QuarkusTransaction.requiringNew().run(() -> channelService.create(uniqueName, "First", ChannelSemantic.APPEND, null));

        assertThrows(Exception.class, () -> QuarkusTransaction.requiringNew()
                .run(() -> channelService.create(uniqueName, "Second", ChannelSemantic.APPEND, null)));

        // Cleanup the committed first record
        QuarkusTransaction.requiringNew().run(() -> Channel.delete("name", uniqueName));
    }

    @Test
    void delete_emptyChannel_succeeds() {
        String name = "del-empty-" + System.nanoTime();
        QuarkusTransaction.requiringNew().run(() -> channelService.create(name, "Test", ChannelSemantic.APPEND, null));
        UUID[] chId = new UUID[1];
        QuarkusTransaction.requiringNew().run(() -> chId[0] = channelService.findByName(name).orElseThrow().id);

        QuarkusTransaction.requiringNew().run(() -> {
            long deleted = channelService.delete(chId[0], false);
            assertEquals(0L, deleted);
        });

        QuarkusTransaction.requiringNew().run(() -> assertTrue(channelService.findByName(name).isEmpty()));
    }

    @Test
    void delete_notFound_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class, () -> QuarkusTransaction.requiringNew()
                .run(() -> channelService.delete(UUID.randomUUID(), false)));
    }

    @Test
    void delete_withMessages_forceTrue_deletesMessagesAndChannel() {
        String name = "del-force-" + System.nanoTime();
        QuarkusTransaction.requiringNew().run(() -> channelService.create(name, "Test", ChannelSemantic.APPEND, null));

        UUID[] chId = new UUID[1];
        QuarkusTransaction.requiringNew().run(() -> chId[0] = channelService.findByName(name).orElseThrow().id);

        QuarkusTransaction.requiringNew()
                .run(() -> messageService.dispatch(                        MessageDispatch.builder()
                        .channelId(chId[0])
                        .sender("agent-a")
                        .type(MessageType.STATUS)
                        .content("hi")
                        .actorType(ActorTypeResolver.resolve("agent-a"))
                        .build()));

        QuarkusTransaction.requiringNew().run(() -> {
            long deleted = channelService.delete(chId[0], true);
            assertEquals(1L, deleted);
        });

        QuarkusTransaction.requiringNew().run(() -> assertTrue(channelService.findByName(name).isEmpty()));
    }

    @Test
    void delete_byUUID_emptyChannel_succeeds() {
        // Verifies the UUID-first delete signature introduced in qhorus#252.
        // Before #252: channelService.delete(UUID, boolean) doesn't exist → compile error (RED).
        // After #252: compiles and behaves identically to delete(name, false).
        String name = "del-uuid-" + System.nanoTime();
        QuarkusTransaction.requiringNew().run(() -> channelService.create(name, "Test", ChannelSemantic.APPEND, null));
        UUID[] chId = new UUID[1];
        QuarkusTransaction.requiringNew().run(() -> chId[0] = channelService.findByName(name).orElseThrow().id);

        QuarkusTransaction.requiringNew().run(() -> {
            long deleted = channelService.delete(chId[0], false); // UUID-first API
            assertEquals(0L, deleted);
        });

        QuarkusTransaction.requiringNew().run(() -> assertTrue(channelService.findByName(name).isEmpty()));
    }

    @Test
    void delete_withMessages_forceFalse_throwsIllegalState() {
        String name = "del-noforce-" + System.nanoTime();
        QuarkusTransaction.requiringNew().run(() -> channelService.create(name, "Test", ChannelSemantic.APPEND, null));

        UUID[] chId = new UUID[1];
        QuarkusTransaction.requiringNew().run(() -> chId[0] = channelService.findByName(name).orElseThrow().id);

        QuarkusTransaction.requiringNew()
                .run(() -> messageService.dispatch(                        MessageDispatch.builder()
                        .channelId(chId[0])
                        .sender("agent-a")
                        .type(MessageType.STATUS)
                        .content("hi")
                        .actorType(ActorTypeResolver.resolve("agent-a"))
                        .build()));

        Exception ex = assertThrows(Exception.class,
                () -> QuarkusTransaction.requiringNew().run(() -> channelService.delete(chId[0], false)));
        assertTrue(ex.getMessage().contains("1") && ex.getMessage().contains("force=true"),
                "Error should mention message count and force=true: " + ex.getMessage());
    }
}
