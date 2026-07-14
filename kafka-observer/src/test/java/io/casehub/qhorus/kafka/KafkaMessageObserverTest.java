package io.casehub.qhorus.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import io.casehub.qhorus.api.gateway.MessageObserver;
import io.casehub.qhorus.api.gateway.MessageReceivedEvent;
import io.casehub.qhorus.api.message.MessageType;
import io.cloudevents.CloudEvent;

class KafkaMessageObserverTest {

    private KafkaMessageObserver observer;
    private final List<KafkaMessageObserver.CloudEventRecord> sent = new ArrayList<>();

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        observer = new KafkaMessageObserver(mapper, sent::add, Set.of());
    }

    @Test
    void scopeIsLocal() {
        assertThat(observer.scope()).isEqualTo(MessageObserver.Scope.LOCAL);
    }

    @Test
    void publishesCloudEventWithCorrectType() {
        UUID channelId = UUID.randomUUID();
        MessageReceivedEvent event = new MessageReceivedEvent(
                1L, "test-channel", channelId, "t1",
                MessageType.STATUS, "agent-1", "corr-1",
                Instant.now(), "hello", "general");

        observer.onMessage(event);

        assertThat(sent).hasSize(1);
        CloudEvent ce = sent.get(0).cloudEvent();
        assertThat(ce.getType()).isEqualTo("io.casehub.qhorus.message.status");
        assertThat(ce.getSource().toString()).contains(channelId.toString());
    }

    @Test
    void kafkaKeyIsChannelId() {
        UUID channelId = UUID.randomUUID();
        MessageReceivedEvent event = new MessageReceivedEvent(
                1L, "test-channel", channelId, "t1",
                MessageType.COMMAND, "agent-1", null,
                Instant.now(), "do it", null);

        observer.onMessage(event);

        assertThat(sent.get(0).key()).isEqualTo(channelId.toString());
    }

    @Test
    void channelFilterReturnsConfiguredSet() {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        KafkaMessageObserver filtered = new KafkaMessageObserver(
                mapper, sent::add, Set.of("channel-a", "channel-b"));

        assertThat(filtered.channels()).containsExactlyInAnyOrder("channel-a", "channel-b");
    }

    @Test
    void emptyChannelFilterMeansAll() {
        assertThat(observer.channels()).isEmpty();
    }

    @Test
    void handlesAllMessageTypes() {
        UUID channelId = UUID.randomUUID();
        for (MessageType type : MessageType.values()) {
            String content = type == MessageType.EVENT ? null : "payload";
            observer.onMessage(new MessageReceivedEvent(
                    1L, "ch", channelId, "t1", type, "a", null,
                    Instant.now(), content, null));
        }
        assertThat(sent).hasSize(MessageType.values().length);
    }
}
