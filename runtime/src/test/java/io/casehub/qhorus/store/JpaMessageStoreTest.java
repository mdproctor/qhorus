package io.casehub.qhorus.store;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.casehub.platform.api.identity.ActorType;
import io.casehub.platform.api.identity.TenancyConstants;
import io.casehub.qhorus.api.channel.Channel;
import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.casehub.qhorus.api.message.Message;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.api.store.ChannelStore;
import io.casehub.qhorus.api.store.MessageStore;
import io.casehub.qhorus.api.store.query.MessageQuery;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class JpaMessageStoreTest {

    @Inject
    MessageStore messageStore;

    @Inject
    ChannelStore channelStore;

    private Channel createChannel() {
        return channelStore.put(Channel.builder("msg-test-" + UUID.randomUUID())
                .semantic(ChannelSemantic.APPEND)
                .tenancyId(TenancyConstants.DEFAULT_TENANT_ID).build());
    }

    private Message buildMessage(UUID channelId, String sender, MessageType type) {
        return Message.builder()
                .channelId(channelId).sender(sender).messageType(type)
                .actorType(ActorType.AGENT)
                .tenancyId(TenancyConstants.DEFAULT_TENANT_ID)
                .content("hello from " + sender).build();
    }

    @Test
    @TestTransaction
    void put_persistsMessageAndAssignsId() {
        Channel ch = createChannel();
        Message saved = messageStore.put(buildMessage(ch.id(), "agent-a", MessageType.COMMAND));

        assertNotNull(saved.id());
        assertEquals("agent-a", saved.sender());
        assertEquals(ch.id(), saved.channelId());
    }

    @Test
    @TestTransaction
    void find_returnsMessage_whenExists() {
        Channel ch = createChannel();
        Message m  = messageStore.put(buildMessage(ch.id(), "agent-b", MessageType.RESPONSE));

        Optional<Message> found = messageStore.find(m.id());

        assertTrue(found.isPresent());
        assertEquals(m.id(), found.get().id());
    }

    @Test
    @TestTransaction
    void find_returnsEmpty_whenNotFound() {
        assertTrue(messageStore.find(Long.MAX_VALUE).isEmpty());
    }

    @Test
    @TestTransaction
    void scan_forChannel_returnsAllChannelMessages() {
        Channel ch = createChannel();
        messageStore.put(buildMessage(ch.id(), "agent-a", MessageType.COMMAND));
        messageStore.put(buildMessage(ch.id(), "agent-b", MessageType.RESPONSE));

        List<Message> results = messageStore.scan(MessageQuery.forChannel(ch.id()));

        assertEquals(2, results.size());
    }

    @Test
    @TestTransaction
    void scan_excludeTypes_omitsExcludedType() {
        Channel ch = createChannel();
        messageStore.put(buildMessage(ch.id(), "agent-a", MessageType.COMMAND));
        messageStore.put(buildMessage(ch.id(), "sys", MessageType.EVENT)
                .toBuilder().content("{\"tool_name\":\"t\",\"duration_ms\":1}").build());

        List<Message> results = messageStore.scan(
                MessageQuery.builder()
                        .channelId(ch.id())
                        .excludeTypes(List.of(MessageType.EVENT))
                        .build());

        assertEquals(1, results.size());
        assertEquals(MessageType.COMMAND, results.get(0).messageType());
    }

    @Test
    @TestTransaction
    void scan_afterId_returnsCursorResults() {
        Channel ch    = createChannel();
        Message first = messageStore.put(buildMessage(ch.id(), "agent-a", MessageType.COMMAND));
        Message second = messageStore.put(buildMessage(ch.id(), "agent-a", MessageType.STATUS));

        List<Message> results = messageStore.scan(
                MessageQuery.builder().channelId(ch.id()).afterId(first.id()).build());

        assertEquals(1, results.size());
        assertEquals(second.id(), results.get(0).id());
    }

    @Test
    @TestTransaction
    void scan_bySender_returnsMatchingOnly() {
        Channel ch = createChannel();
        messageStore.put(buildMessage(ch.id(), "agent-a", MessageType.COMMAND));
        messageStore.put(buildMessage(ch.id(), "agent-b", MessageType.COMMAND));

        List<Message> results = messageStore.scan(
                MessageQuery.builder().channelId(ch.id()).sender("agent-a").build());

        assertEquals(1, results.size());
        assertEquals("agent-a", results.get(0).sender());
    }

    @Test
    @TestTransaction
    void scan_contentPattern_returnsMatchingOnly() {
        Channel ch = createChannel();
        messageStore.put(buildMessage(ch.id(), "agent-a", MessageType.COMMAND)
                .toBuilder().content("special-keyword-here").build());
        messageStore.put(buildMessage(ch.id(), "agent-b", MessageType.COMMAND));

        List<Message> results = messageStore.scan(
                MessageQuery.builder().channelId(ch.id()).contentPattern("special-keyword").build());

        assertEquals(1, results.size());
    }

    @Test
    @TestTransaction
    void scan_inReplyTo_returnsOnlyReplies() {
        Channel ch     = createChannel();
        Message parent = messageStore.put(buildMessage(ch.id(), "agent-a", MessageType.COMMAND));
        Message reply  = messageStore.put(buildMessage(ch.id(), "agent-b", MessageType.RESPONSE)
                .toBuilder().inReplyTo(parent.id()).build());

        List<Message> results = messageStore.scan(
                MessageQuery.replies(ch.id(), parent.id()));

        assertEquals(1, results.size());
        assertEquals(reply.id(), results.get(0).id());
    }

    @Test
    @TestTransaction
    void countByChannel_returnsCorrectCount() {
        Channel ch = createChannel();
        messageStore.put(buildMessage(ch.id(), "agent-a", MessageType.COMMAND));
        messageStore.put(buildMessage(ch.id(), "agent-b", MessageType.RESPONSE));

        assertEquals(2, messageStore.countByChannel(ch.id()));
    }

    @Test
    @TestTransaction
    void deleteAll_removesAllChannelMessages() {
        Channel ch = createChannel();
        messageStore.put(buildMessage(ch.id(), "agent-a", MessageType.COMMAND));
        messageStore.put(buildMessage(ch.id(), "agent-b", MessageType.RESPONSE));

        messageStore.deleteAll(ch.id());

        assertEquals(0, messageStore.countByChannel(ch.id()));
    }

    @Test
    @TestTransaction
    void delete_removesSpecificMessage() {
        Channel ch = createChannel();
        Message m  = messageStore.put(buildMessage(ch.id(), "agent-a", MessageType.COMMAND));

        messageStore.delete(m.id());

        assertTrue(messageStore.find(m.id()).isEmpty());
    }

    @Test
    @TestTransaction
    void countAllByChannel_returnsCorrectCountsPerChannel() {
        Channel ch1 = createChannel();
        Channel ch2 = createChannel();
        messageStore.put(buildMessage(ch1.id(), "agent-a", MessageType.COMMAND));
        messageStore.put(buildMessage(ch1.id(), "agent-b", MessageType.RESPONSE));
        messageStore.put(buildMessage(ch2.id(), "agent-c", MessageType.STATUS));

        Map<UUID, Long> counts = messageStore.countAllByChannel();

        assertTrue(counts.containsKey(ch1.id()));
        assertTrue(counts.containsKey(ch2.id()));
        assertEquals(2L, counts.get(ch1.id()));
        assertEquals(1L, counts.get(ch2.id()));
    }

    @Test
    @TestTransaction
    void countAllByChannel_doesNotContainChannelWithNoMessages() {
        Channel isolatedChannel = createChannel();

        Map<UUID, Long> counts = messageStore.countAllByChannel();

        assertFalse(counts.containsKey(isolatedChannel.id()),
                "Channel with no messages must not appear in countAllByChannel result");
    }

    @Test
    @TestTransaction
    void distinctSendersByChannel_excludesSpecifiedType_andDeduplicates() {
        Channel ch = createChannel();
        messageStore.put(buildMessage(ch.id(), "agent-a", MessageType.COMMAND));
        messageStore.put(buildMessage(ch.id(), "agent-a", MessageType.STATUS));
        messageStore.put(buildMessage(ch.id(), "agent-b", MessageType.RESPONSE));
        messageStore.put(buildMessage(ch.id(), "sys-monitor", MessageType.EVENT)
                .toBuilder().content("{\"tool_name\":\"t\",\"duration_ms\":1}").build());

        List<String> senders = messageStore.distinctSendersByChannel(ch.id(), MessageType.EVENT);

        assertTrue(senders.contains("agent-a"));
        assertTrue(senders.contains("agent-b"));
        assertFalse(senders.contains("sys-monitor"));
        assertEquals(1, senders.stream().filter("agent-a"::equals).count());
    }

    @Test
    @TestTransaction
    void count_byChannel_excludesEventType() {
        Channel ch = createChannel();
        messageStore.put(buildMessage(ch.id(), "agent-a", MessageType.COMMAND));
        messageStore.put(buildMessage(ch.id(), "agent-b", MessageType.RESPONSE));
        messageStore.put(buildMessage(ch.id(), "agent-c", MessageType.EVENT));

        long result = messageStore.count(
                MessageQuery.builder()
                        .channelId(ch.id())
                        .excludeTypes(java.util.List.of(MessageType.EVENT))
                        .build());

        assertEquals(2, result);
    }

    @Test
    @TestTransaction
    void scan_descending_returnsNewestFirst() {
        Channel ch    = createChannel();
        Message first = messageStore.put(buildMessage(ch.id(), "agent-a", MessageType.COMMAND));
        Message second = messageStore.put(buildMessage(ch.id(), "agent-b", MessageType.STATUS));
        Message third  = messageStore.put(buildMessage(ch.id(), "agent-c", MessageType.RESPONSE));

        List<Message> results = messageStore.scan(
                MessageQuery.builder()
                        .channelId(ch.id())
                        .descending(true)
                        .limit(2)
                        .build());

        assertEquals(2, results.size());
        assertEquals(third.id(), results.get(0).id());
        assertEquals(second.id(), results.get(1).id());
    }
}
