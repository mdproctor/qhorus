package io.casehub.qhorus.persistence.memory;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.casehub.qhorus.runtime.channel.Channel;
import io.casehub.qhorus.runtime.message.Message;
import io.casehub.qhorus.runtime.store.query.ChannelQuery;
import io.casehub.qhorus.runtime.store.query.MessageQuery;

class InMemoryStoresDualInterfaceTest {

    InMemoryChannelStore channelStore = new InMemoryChannelStore();
    InMemoryMessageStore messageStore = new InMemoryMessageStore();
    InMemoryReactiveChannelStore reactiveChannelStore;
    InMemoryReactiveMessageStore reactiveMessageStore;

    @BeforeEach
    void setUp() {
        reactiveChannelStore = new InMemoryReactiveChannelStore();
        reactiveChannelStore.blocking = channelStore;
        reactiveMessageStore = new InMemoryReactiveMessageStore();
        reactiveMessageStore.blocking = messageStore;
    }

    @AfterEach
    void tearDown() {
        channelStore.clear();
        messageStore.clear();
    }

    @Test
    void blockingChannelPut_visibleToReactiveFindByName() {
        Channel ch = new Channel();
        ch.name = "work";
        ch.semantic = ChannelSemantic.APPEND;
        channelStore.put(ch);

        Optional<Channel> found = reactiveChannelStore.findByName("work")
                .await().atMost(Duration.ofSeconds(1));

        assertThat(found).isPresent();
        assertThat(found.get().name).isEqualTo("work");
    }

    @Test
    void blockingChannelPut_visibleToReactiveScan() {
        Channel ch = new Channel();
        ch.name = "observe";
        ch.semantic = ChannelSemantic.APPEND;
        channelStore.put(ch);

        List<Channel> found = reactiveChannelStore.scan(ChannelQuery.all())
                .await().atMost(Duration.ofSeconds(1));

        assertThat(found).hasSize(1);
    }

    @Test
    void blockingChannelClear_clearsReactiveView() {
        Channel ch = new Channel();
        ch.name = "work";
        ch.semantic = ChannelSemantic.APPEND;
        channelStore.put(ch);
        channelStore.clear();

        List<Channel> found = reactiveChannelStore.scan(ChannelQuery.all())
                .await().atMost(Duration.ofSeconds(1));

        assertThat(found).isEmpty();
    }

    @Test
    void blockingMessagePut_visibleToReactiveScan() {
        Channel ch = new Channel();
        ch.id = UUID.randomUUID();
        ch.name = "work";
        ch.semantic = ChannelSemantic.APPEND;

        Message msg = new Message();
        msg.channelId = ch.id;
        msg.sender = "agent:analyst@v1";
        messageStore.put(msg);

        List<Message> found = reactiveMessageStore.scan(MessageQuery.forChannel(ch.id))
                .await().atMost(Duration.ofSeconds(1));

        assertThat(found).hasSize(1);
        assertThat(found.get(0).sender).isEqualTo("agent:analyst@v1");
    }

    @Test
    void blockingMessageCountByChannel_matchesReactiveCount() {
        UUID channelId = UUID.randomUUID();
        for (int i = 0; i < 3; i++) {
            Message msg = new Message();
            msg.channelId = channelId;
            messageStore.put(msg);
        }

        int reactiveCount = reactiveMessageStore.countByChannel(channelId)
                .await().atMost(Duration.ofSeconds(1));

        assertThat(reactiveCount).isEqualTo(3);
    }
}
