package io.casehub.qhorus.store.reactive;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import io.casehub.qhorus.api.message.Message;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.api.store.ReactiveMessageStore;
import io.casehub.qhorus.api.store.query.MessageQuery;
import io.quarkus.hibernate.reactive.panache.Panache;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.vertx.RunOnVertxContext;
import io.quarkus.test.vertx.UniAsserter;

@Disabled("Requires reactive datasource — H2 has no reactive driver; run with Dev Services/PostgreSQL")
@QuarkusTest
@TestProfile(ReactiveStoreTestProfile.class)
class ReactiveJpaMessageStoreTest {

    @Inject
    ReactiveMessageStore store;

    @Test
    @RunOnVertxContext
    void put_assignsIdAndReturns(UniAsserter asserter) {
        Message m = message(UUID.randomUUID(), "alice");
        asserter.assertThat(
                () -> Panache.withTransaction("qhorus", () -> store.put(m)),
                saved -> assertNotNull(saved.id()));
    }

    @Test
    @RunOnVertxContext
    void find_returnsEmpty_whenNotFound(UniAsserter asserter) {
        asserter.assertThat(
                () -> store.find(Long.MAX_VALUE),
                opt -> assertTrue(opt.isEmpty()));
    }

    @Test
    @RunOnVertxContext
    void scan_byChannel_returnsMatchingMessages(UniAsserter asserter) {
        UUID    ch1 = UUID.randomUUID();
        UUID    ch2 = UUID.randomUUID();
        Message m1  = message(ch1, "alice");
        Message m2  = message(ch2, "bob");

        asserter
                .execute(() -> Panache.withTransaction("qhorus", () -> store.put(m1)))
                .execute(() -> Panache.withTransaction("qhorus", () -> store.put(m2)))
                .assertThat(
                        () -> store.scan(MessageQuery.forChannel(ch1)),
                        results -> {
                            assertEquals(1, results.size());
                            assertEquals("alice", results.get(0).sender());
                        });
    }

    @Test
    @RunOnVertxContext
    void countByChannel_returnsCorrectCount(UniAsserter asserter) {
        UUID ch = UUID.randomUUID();
        asserter
                .execute(() -> Panache.withTransaction("qhorus", () -> store.put(message(ch, "x"))))
                .execute(() -> Panache.withTransaction("qhorus", () -> store.put(message(ch, "y"))))
                .assertThat(
                        () -> store.countByChannel(ch),
                        count -> assertEquals(2, count));
    }

    @Test
    @RunOnVertxContext
    void deleteAll_removesAllMessagesForChannel(UniAsserter asserter) {
        UUID ch = UUID.randomUUID();
        asserter
                .execute(() -> Panache.withTransaction("qhorus", () -> store.put(message(ch, "a"))))
                .execute(() -> Panache.withTransaction("qhorus", () -> store.put(message(ch, "b"))))
                .execute(() -> store.deleteAll(ch))
                .assertThat(
                        () -> store.countByChannel(ch),
                        count -> assertEquals(0, count));
    }

    private Message message(UUID channelId, String sender) {
        return Message.builder().channelId(channelId).sender(sender)
                .messageType(MessageType.COMMAND).content("hello").build();
    }
}
