package io.casehub.qhorus.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.casehub.platform.api.identity.ActorTypeResolver;
import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.casehub.qhorus.api.message.DispatchResult;
import io.casehub.qhorus.api.message.MessageDispatch;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.api.channel.Channel;
import io.casehub.qhorus.runtime.channel.ChannelEntity;
import io.casehub.qhorus.api.channel.ChannelCreateRequest;
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
    void createChannelPersistsAllFields() {
        String name = "auth-refactor-" + System.nanoTime();
        QuarkusTransaction.requiringNew().run(() -> {
            Channel ch = channelService.create(ChannelCreateRequest.builder(name).description("Auth refactoring thread").build());

            assertNotNull(ch.id());
            assertEquals(name, ch.name());
            assertEquals("Auth refactoring thread", ch.description());
            assertEquals(ChannelSemantic.APPEND, ch.semantic());
            assertThat(ch.barrierContributors()).isEmpty();
            assertNotNull(ch.createdAt());
            assertNotNull(ch.lastActivityAt());
        });
    }

    @Test
    void createChannelWithBarrierContributors() {
        String name = "sync-point-" + System.nanoTime();
        QuarkusTransaction.requiringNew().run(() -> {
            Channel ch = channelService.create(ChannelCreateRequest.builder(name).description("Wait for all reviewers").semantic(ChannelSemantic.BARRIER).barrierContributors(List.of("alice", "bob", "carol")).build());

            assertEquals(ChannelSemantic.BARRIER, ch.semantic());
            assertEquals(List.of("alice", "bob", "carol"), ch.barrierContributors());
        });
    }

    @Test
    void findByNameReturnsChannel() {
        String name = "findings-" + System.nanoTime();
        QuarkusTransaction.requiringNew().run(() ->
                channelService.create(ChannelCreateRequest.builder(name).description("Research findings").semantic(ChannelSemantic.COLLECT).build()));

        QuarkusTransaction.requiringNew().run(() -> {
            Optional<Channel> found = channelService.findByName(name);

            assertTrue(found.isPresent());
            assertEquals(name, found.get().name());
            assertEquals(ChannelSemantic.COLLECT, found.get().semantic());
        });
    }

    @Test
    void findByNameReturnsEmptyWhenNotFound() {
        QuarkusTransaction.requiringNew().run(() -> {
            Optional<Channel> found = channelService.findByName("no-such-channel-" + System.nanoTime());

            assertTrue(found.isEmpty());
        });
    }

    @Test
    void listAllReturnsCreatedChannels() {
        String alpha = "alpha-" + System.nanoTime();
        String beta = "beta-" + System.nanoTime();
        QuarkusTransaction.requiringNew().run(() -> {
            channelService.create(ChannelCreateRequest.builder(alpha).description("Alpha channel").build());
            channelService.create(ChannelCreateRequest.builder(beta).description("Beta channel").semantic(ChannelSemantic.LAST_WRITE).build());
        });

        QuarkusTransaction.requiringNew().run(() -> {
            List<Channel> channels = channelService.listAll();

            assertTrue(channels.size() >= 2);
            assertTrue(channels.stream().anyMatch(c -> alpha.equals(c.name())));
            assertTrue(channels.stream().anyMatch(c -> beta.equals(c.name())));
        });
    }

    @Test
    void updateLastActivityAdvancesTimestamp() throws InterruptedException {
        String name = "active-ch-" + System.nanoTime();
        Channel[] holder = new Channel[1];
        QuarkusTransaction.requiringNew().run(() ->
                holder[0] = channelService.create(ChannelCreateRequest.builder(name).description("Active channel").build()));
        Instant original = holder[0].lastActivityAt();

        Thread.sleep(5);
        QuarkusTransaction.requiringNew().run(() ->
                channelService.updateLastActivity(holder[0].id(), holder[0].tenancyId()));

        QuarkusTransaction.requiringNew().run(() -> {
            Channel updated = channelService.findByName(name).orElseThrow();
            assertTrue(updated.lastActivityAt().isAfter(original),
                    "lastActivityAt should advance after updateLastActivity");
        });
    }

    @Test
    void createWithAllowedTypes_storesConstraint() {
        String name = "allowed-types-" + System.nanoTime();
        QuarkusTransaction.requiringNew().run(() -> {
            Channel ch = channelService.create(ChannelCreateRequest.builder(name)
                                                                         .description("Telemetry").allowedTypes(Set.of(MessageType.EVENT)).build());
            assertEquals(Set.of(MessageType.EVENT), ch.allowedTypes());
        });
    }

    @Test
    void createWithNullAllowedTypes_storesNull() {
        String name = "no-allowed-types-" + System.nanoTime();
        QuarkusTransaction.requiringNew().run(() -> {
            Channel ch = channelService.create(ChannelCreateRequest.builder(name)
                                                                         .description("Open").build());
            assertNull(ch.allowedTypes());
        });
    }

    @Test
    void createWithEmptyAllowedTypes_storesNull() {
        // Empty Set<MessageType> serializes to null — same DB representation as "no constraint"
        String name = "empty-allowed-types-" + System.nanoTime();
        QuarkusTransaction.requiringNew().run(() -> {
            Channel ch = channelService.create(ChannelCreateRequest.builder(name)
                                                                         .description("Open").allowedTypes(Set.of()).build());
            assertNull(ch.allowedTypes());
        });
    }

    @Test
    void existingFourParamOverload_setsNullAllowedTypes() {
        String name = "legacy-overload-" + System.nanoTime();
        QuarkusTransaction.requiringNew().run(() -> {
            Channel ch = channelService.create(ChannelCreateRequest.builder(name).description("Legacy").build());
            assertNull(ch.allowedTypes());
        });
    }

    // ------------------------------------------------------------------
    // denied types — creation and dispatch enforcement
    // ------------------------------------------------------------------

    @Test
    void createWithDeniedTypes_storesDeniedTypes() {
        String name = "denied-types-" + System.nanoTime();
        QuarkusTransaction.requiringNew().run(() -> {
            Channel ch = channelService.create(ChannelCreateRequest.builder(name)
                                                                         .description("Oversight").deniedTypes(Set.of(MessageType.EVENT)).build());
            assertEquals(Set.of(MessageType.EVENT), ch.deniedTypes());
            assertNull(ch.allowedTypes());
        });
        QuarkusTransaction.requiringNew().run(() -> ChannelEntity.delete("name", name));
    }

    @Test
    void createWithOverlappingTypes_throwsAtCreation() {
        assertThrows(IllegalArgumentException.class, () ->
                QuarkusTransaction.requiringNew().run(() ->
                        channelService.create(ChannelCreateRequest.builder("overlap-" + System.nanoTime())
                                .description("Bad")
                                .allowedTypes(Set.of(MessageType.QUERY)).deniedTypes(Set.of(MessageType.QUERY))
                                .build())));
    }

    @Test
    void dispatch_deniedType_throwsViolation() throws InterruptedException {
        String name = "denied-event-" + System.nanoTime();
        QuarkusTransaction.requiringNew().run(() ->
                channelService.create(ChannelCreateRequest.builder(name)
                        .description("Oversight").deniedTypes(Set.of(MessageType.EVENT))
                        .build()));
        UUID[] chId = new UUID[1];
        QuarkusTransaction.requiringNew().run(() -> chId[0] = channelService.findByName(name).orElseThrow().id());

        // EVENT is not obligation-creating — dispatch succeeds with advisory
        DispatchResult[] result = new DispatchResult[1];
        QuarkusTransaction.requiringNew().run(() ->
                result[0] = messageService.dispatch(MessageDispatch.builder()
                        .channelId(chId[0])
                        .sender("telemetry-agent")
                        .type(MessageType.EVENT)
                        .telemetry("{\"tool\":\"search\"}")
                        .actorType(ActorTypeResolver.resolve("telemetry-agent"))
                        .build()));
        assertFalse(result[0].advisories().isEmpty(), "Expected advisory for denied EVENT");

        // non-denied type passes
        QuarkusTransaction.requiringNew().run(() ->
                messageService.dispatch(MessageDispatch.builder()
                        .channelId(chId[0])
                        .sender("overseer")
                        .type(MessageType.COMMAND)
                        .content("proceed")
                        .actorType(ActorTypeResolver.resolve("overseer"))
                        .build()));

        QuarkusTransaction.requiringNew().run(() -> ChannelEntity.delete("name", name));
    }

    @Test
    void duplicateChannelNameThrowsException() {
        // Use explicit transactions so each commit is independent and the unique
        // constraint is actually checked against the DB (not just the Hibernate cache)
        String uniqueName = "dup-test-" + System.nanoTime();
        QuarkusTransaction.requiringNew().run(() -> channelService.create(ChannelCreateRequest.builder(uniqueName).description("First").build()));

        assertThrows(Exception.class, () -> QuarkusTransaction.requiringNew()
                .run(() -> channelService.create(ChannelCreateRequest.builder(uniqueName).description("Second").build())));

        // Cleanup the committed first record
        QuarkusTransaction.requiringNew().run(() -> ChannelEntity.delete("name", uniqueName));
    }

    @Test
    void delete_emptyChannel_succeeds() {
        String name = "del-empty-" + System.nanoTime();
        QuarkusTransaction.requiringNew().run(() -> channelService.create(ChannelCreateRequest.builder(name).description("Test").build()));
        UUID[] chId = new UUID[1];
        QuarkusTransaction.requiringNew().run(() -> chId[0] = channelService.findByName(name).orElseThrow().id());

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
        QuarkusTransaction.requiringNew().run(() -> channelService.create(ChannelCreateRequest.builder(name).description("Test").build()));

        UUID[] chId = new UUID[1];
        QuarkusTransaction.requiringNew().run(() -> chId[0] = channelService.findByName(name).orElseThrow().id());

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
        QuarkusTransaction.requiringNew().run(() -> channelService.create(ChannelCreateRequest.builder(name).description("Test").build()));
        UUID[] chId = new UUID[1];
        QuarkusTransaction.requiringNew().run(() -> chId[0] = channelService.findByName(name).orElseThrow().id());

        QuarkusTransaction.requiringNew().run(() -> {
            long deleted = channelService.delete(chId[0], false); // UUID-first API
            assertEquals(0L, deleted);
        });

        QuarkusTransaction.requiringNew().run(() -> assertTrue(channelService.findByName(name).isEmpty()));
    }

    @Test
    void delete_withMessages_forceFalse_throwsIllegalState() {
        String name = "del-noforce-" + System.nanoTime();
        QuarkusTransaction.requiringNew().run(() -> channelService.create(ChannelCreateRequest.builder(name).description("Test").build()));

        UUID[] chId = new UUID[1];
        QuarkusTransaction.requiringNew().run(() -> chId[0] = channelService.findByName(name).orElseThrow().id());

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

    @Test
    void setTypeConstraints_withNullSets_clearsConstraints() {
        // Verifies that null→Set.of()→serializeTypes(Set.of())→null correctly clears constraints
        String name = "clear-constraints-" + System.nanoTime();
        QuarkusTransaction.requiringNew().run(() -> channelService.create(ChannelCreateRequest.builder(name)
                .description("test")
                .allowedTypes(Set.of(MessageType.EVENT)).deniedTypes(Set.of(MessageType.QUERY))
                .build()));
        UUID[] chId = new UUID[1];
        QuarkusTransaction.requiringNew().run(() -> chId[0] = channelService.findByName(name).orElseThrow().id());
        QuarkusTransaction.requiringNew().run(() -> {
            Channel ch = channelService.setTypeConstraints(chId[0], null, null);
            assertNull(ch.allowedTypes(), "null Set should clear allowedTypes");
            assertNull(ch.deniedTypes(), "null Set should clear deniedTypes");
        });
    }
}
