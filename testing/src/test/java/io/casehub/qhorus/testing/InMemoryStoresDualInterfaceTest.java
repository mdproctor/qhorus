package io.casehub.qhorus.testing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.casehub.qhorus.api.channel.Channel;
import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.casehub.qhorus.api.message.Message;
import io.casehub.qhorus.api.store.query.ChannelQuery;
import io.casehub.qhorus.api.store.query.MessageQuery;

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
        channelStore.put(Channel.builder("work").semantic(ChannelSemantic.APPEND).build());

        Optional<Channel> found = reactiveChannelStore.findByName("work")
                .await().atMost(Duration.ofSeconds(1));

        assertThat(found).isPresent();
        assertThat(found.get().name()).isEqualTo("work");
    }

    @Test
    void blockingChannelPut_visibleToReactiveScan() {
        channelStore.put(Channel.builder("observe").semantic(ChannelSemantic.APPEND).build());

        List<Channel> found = reactiveChannelStore.scan(ChannelQuery.all())
                .await().atMost(Duration.ofSeconds(1));

        assertThat(found).hasSize(1);
    }

    @Test
    void blockingChannelClear_clearsReactiveView() {
        channelStore.put(Channel.builder("work").semantic(ChannelSemantic.APPEND).build());
        channelStore.clear();

        List<Channel> found = reactiveChannelStore.scan(ChannelQuery.all())
                .await().atMost(Duration.ofSeconds(1));

        assertThat(found).isEmpty();
    }

    @Test
    void blockingMessagePut_visibleToReactiveScan() {
        UUID chId = UUID.randomUUID();
        messageStore.put(Message.builder().channelId(chId).sender("agent:analyst@v1").build());

        List<Message> found = reactiveMessageStore.scan(MessageQuery.forChannel(chId))
                .await().atMost(Duration.ofSeconds(1));

        assertThat(found).hasSize(1);
        assertThat(found.get(0).sender()).isEqualTo("agent:analyst@v1");
    }

    @Test
    void blockingMessageCountByChannel_matchesReactiveCount() {
        UUID channelId = UUID.randomUUID();
        for (int i = 0; i < 3; i++) {
            messageStore.put(Message.builder().channelId(channelId).build());
        }

        int reactiveCount = reactiveMessageStore.countByChannel(channelId)
                .await().atMost(Duration.ofSeconds(1));

        assertThat(reactiveCount).isEqualTo(3);
    }
}
