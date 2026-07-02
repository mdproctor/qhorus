package io.casehub.qhorus.store.reactive;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import io.casehub.platform.api.identity.TenancyConstants;
import io.casehub.qhorus.api.channel.Channel;
import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.casehub.qhorus.api.store.ReactiveChannelStore;
import io.casehub.qhorus.api.store.query.ChannelQuery;
import io.quarkus.hibernate.reactive.panache.Panache;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.vertx.RunOnVertxContext;
import io.quarkus.test.vertx.UniAsserter;

@Disabled("Requires reactive datasource — H2 has no reactive driver; run with Dev Services/PostgreSQL")
@QuarkusTest
@TestProfile(ReactiveStoreTestProfile.class)
class ReactiveJpaChannelStoreTest {

    @Inject
    ReactiveChannelStore store;

    @Test
    @RunOnVertxContext
    void put_persistsChannelAndAssignsId(UniAsserter asserter) {
        Channel ch = Channel.builder("rx-put-" + UUID.randomUUID())
                .semantic(ChannelSemantic.APPEND).build();

        asserter.assertThat(
                () -> Panache.withTransaction("qhorus", () -> store.put(ch)),
                saved -> {
                    assertNotNull(saved.id());
                    assertEquals(ChannelSemantic.APPEND, saved.semantic());
                });
    }

    @Test
    @RunOnVertxContext
    void find_returnsEmpty_whenNotFound(UniAsserter asserter) {
        asserter.assertThat(
                () -> store.find(UUID.randomUUID()),
                opt -> assertTrue(opt.isEmpty()));
    }

    @Test
    @RunOnVertxContext
    void findByName_returnsChannel_whenExists(UniAsserter asserter) {
        String name = "rx-named-" + UUID.randomUUID();
        Channel ch = Channel.builder(name).semantic(ChannelSemantic.COLLECT).build();

        asserter
                .execute(() -> Panache.withTransaction("qhorus", () -> store.put(ch)))
                .assertThat(
                        () -> store.findByName(name),
                        opt -> {
                            assertTrue(opt.isPresent());
                            assertEquals(ChannelSemantic.COLLECT, opt.get().semantic());
                        });
    }

    @Test
    @RunOnVertxContext
    void scan_pausedOnly_returnsOnlyPaused(UniAsserter asserter) {
        String activeName = "rx-active-" + UUID.randomUUID();
        String pausedName = "rx-paused-" + UUID.randomUUID();
        Channel active = Channel.builder(activeName).semantic(ChannelSemantic.APPEND).build();
        Channel paused = Channel.builder(pausedName).semantic(ChannelSemantic.APPEND).paused(true).build();

        asserter
                .execute(() -> Panache.withTransaction("qhorus", () -> store.put(active)))
                .execute(() -> Panache.withTransaction("qhorus", () -> store.put(paused)))
                .assertThat(
                        () -> store.scan(ChannelQuery.pausedOnly()),
                        results -> {
                            assertTrue(results.stream().anyMatch(c -> c.name().equals(pausedName)));
                            assertTrue(results.stream().noneMatch(c -> c.name().equals(activeName)));
                        });
    }

    @Test
    @RunOnVertxContext
    void delete_removesChannel(UniAsserter asserter) {
        Channel ch = Channel.builder("rx-del-" + UUID.randomUUID())
                .semantic(ChannelSemantic.APPEND).build();
        final UUID[] savedId = new UUID[1];

        asserter
                .execute(() -> Panache.withTransaction("qhorus", () -> store.put(ch))
                        .invoke(saved -> savedId[0] = saved.id()))
                .execute(() -> store.delete(savedId[0]))
                .assertThat(
                        () -> store.find(savedId[0]),
                        opt -> assertTrue(opt.isEmpty()));
    }

    @Test
    @RunOnVertxContext
    void updateLastActivity_setsTimestamp_withoutBindingError(UniAsserter asserter) {
        Channel ch = Channel.builder("rx-activity-" + UUID.randomUUID())
                .semantic(ChannelSemantic.APPEND)
                .tenancyId(TenancyConstants.DEFAULT_TENANT_ID).build();
        final UUID[] savedId = new UUID[1];

        asserter
                .execute(() -> Panache.withTransaction("qhorus", () -> store.put(ch))
                        .invoke(saved -> savedId[0] = saved.id()))
                .execute(() -> store.updateLastActivity(savedId[0], TenancyConstants.DEFAULT_TENANT_ID))
                .assertThat(
                        () -> Panache.withSession("qhorus", () -> store.find(savedId[0])),
                        opt -> {
                            assertTrue(opt.isPresent());
                            assertNotNull(opt.get().lastActivityAt());
                        });
    }
}
