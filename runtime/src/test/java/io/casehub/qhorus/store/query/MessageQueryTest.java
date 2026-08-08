package io.casehub.qhorus.store.query;

import io.casehub.qhorus.api.message.Message;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.api.store.query.MessageQuery;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessageQueryTest {

    private static final UUID CHANNEL_A = UUID.randomUUID();
    private static final UUID CHANNEL_B = UUID.randomUUID();

    private Message msg(UUID channelId, MessageType type) {
        return Message.builder().channelId(channelId).messageType(type).sender("agent-1").build();
    }

    @Test
    void forChannel_matchesSameChannel() {
        assertTrue(MessageQuery.forChannel(CHANNEL_A).matches(msg(CHANNEL_A, MessageType.COMMAND)));
    }

    @Test
    void forChannel_doesNotMatchDifferentChannel() {
        assertFalse(MessageQuery.forChannel(CHANNEL_A).matches(msg(CHANNEL_B, MessageType.COMMAND)));
    }

    @Test
    void poll_filtersById() {
        Message m = msg(CHANNEL_A, MessageType.COMMAND).toBuilder().id(10L).build();

        assertTrue(MessageQuery.poll(CHANNEL_A, 5L, 20).matches(m));
        assertFalse(MessageQuery.poll(CHANNEL_A, 10L, 20).matches(m));
        assertFalse(MessageQuery.poll(CHANNEL_A, 15L, 20).matches(m));
    }

    @Test
    void excludeTypes_filtersOutMatchingType() {
        Message m = msg(CHANNEL_A, MessageType.EVENT);

        MessageQuery excludeEvents = MessageQuery.builder()
                .channelId(CHANNEL_A)
                .excludeTypes(List.of(MessageType.EVENT))
                .build();
        assertFalse(excludeEvents.matches(m));

        MessageQuery excludeRequests = MessageQuery.builder()
                .channelId(CHANNEL_A)
                .excludeTypes(List.of(MessageType.COMMAND))
                .build();
        assertTrue(excludeRequests.matches(m));
    }

    @Test
    void sender_filtersCorrectly() {
        Message m = msg(CHANNEL_A, MessageType.COMMAND);

        assertTrue(MessageQuery.builder().sender("agent-1").build().matches(m));
        assertFalse(MessageQuery.builder().sender("agent-2").build().matches(m));
    }

    @Test
    void target_filtersCorrectly() {
        Message m = msg(CHANNEL_A, MessageType.COMMAND).toBuilder().target("instance:abc").build();

        assertTrue(MessageQuery.builder().target("instance:abc").build().matches(m));
        assertFalse(MessageQuery.builder().target("instance:xyz").build().matches(m));
    }

    @Test
    void replies_matchesOnInReplyTo() {
        Message m = msg(CHANNEL_A, MessageType.RESPONSE).toBuilder().inReplyTo(42L).build();

        assertTrue(MessageQuery.replies(CHANNEL_A, 42L).matches(m));
        assertFalse(MessageQuery.replies(CHANNEL_A, 99L).matches(m));
    }

    @Test
    void contentPattern_caseInsensitiveSubstring() {
        Message m = msg(CHANNEL_A, MessageType.EVENT).toBuilder()
                .content("Task completed successfully").build();

        assertTrue(MessageQuery.builder().contentPattern("COMPLETED").build().matches(m));
        assertFalse(MessageQuery.builder().contentPattern("failed").build().matches(m));
    }

    @Test
    void contentPattern_doesNotMatchNullContent() {
        Message m = msg(CHANNEL_A, MessageType.COMMAND);

        assertFalse(MessageQuery.builder().contentPattern("anything").build().matches(m));
    }

    @Test
    void builder_combinesMultiplePredicates() {
        Message m = msg(CHANNEL_A, MessageType.COMMAND).toBuilder()
                .sender("orchestrator").id(20L).build();

        MessageQuery q = MessageQuery.builder()
                .channelId(CHANNEL_A)
                .sender("orchestrator")
                .afterId(10L)
                .excludeTypes(List.of(MessageType.EVENT))
                .build();

        assertTrue(q.matches(m));

        Message other = m.toBuilder().sender("other-agent").build();
        assertFalse(q.matches(other));
    }

    @Test
    void recent_createsDescendingQueryWithNoChannel() {
        MessageQuery q = MessageQuery.recent(50);
        assertNull(q.channelId());
        assertEquals(50, q.limit());
        assertTrue(q.descending());
        assertNull(q.afterId());
    }

    @Test
    void beforeId_inclusiveUpperBound() {
        Message m95  = msg(CHANNEL_A, MessageType.STATUS).toBuilder().id(95L).build();
        Message m100 = msg(CHANNEL_A, MessageType.STATUS).toBuilder().id(100L).build();
        Message m101 = msg(CHANNEL_A, MessageType.STATUS).toBuilder().id(101L).build();

        MessageQuery query = MessageQuery.builder()
                                         .channelId(CHANNEL_A)
                                         .afterId(50L)
                                         .beforeId(100L)
                                         .build();

        assertTrue(query.matches(m95));
        assertTrue(query.matches(m100));
        assertFalse(query.matches(m101));
    }

    @Test
    void beforeId_nullMeansUnbounded() {
        Message m = msg(CHANNEL_A, MessageType.STATUS).toBuilder().id(1000L).build();

        MessageQuery query = MessageQuery.builder()
                                         .channelId(CHANNEL_A)
                                         .build();

        assertTrue(query.matches(m));
    }
}
