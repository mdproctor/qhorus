package io.casehub.qhorus.runtime.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.casehub.qhorus.api.gateway.MessageReceivedEvent;
import io.casehub.qhorus.api.message.MessageType;
import io.cloudevents.CloudEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CloudEventMapperTest {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void mapsMessageReceivedEventToCloudEvent() {
        UUID channelId = UUID.randomUUID();
        Instant now = Instant.now();
        MessageReceivedEvent event = new MessageReceivedEvent(
                1L, "test-channel", channelId, "tenant-1",
                MessageType.STATUS, "agent-1", "corr-1", now, "hello", "general");

        CloudEvent ce = CloudEventMapper.toCloudEvent(event, mapper);

        assertThat(ce.getType()).isEqualTo("io.casehub.qhorus.message.status");
        assertThat(ce.getSource().toString()).isEqualTo("/casehub-qhorus/channel/" + channelId);
        assertThat(ce.getSubject()).isEqualTo("channel/" + channelId);
        assertThat(ce.getTime()).isNotNull();
        assertThat(ce.getDataContentType()).isEqualTo("application/json");
        assertThat(ce.getData()).isNotNull();
        assertThat(ce.getExtension("tenancyid")).isEqualTo("tenant-1");
    }

    @Test
    void omitsTenancyExtensionWhenNull() {
        UUID channelId = UUID.randomUUID();
        MessageReceivedEvent event = new MessageReceivedEvent(
                1L, "test-channel", channelId, null,
                MessageType.QUERY, "agent-1", null, Instant.now(), "q", null);

        CloudEvent ce = CloudEventMapper.toCloudEvent(event, mapper);

        assertThat(ce.getExtension("tenancyid")).isNull();
    }

    @Test
    void handlesEventTypeWithNullContent() {
        UUID channelId = UUID.randomUUID();
        MessageReceivedEvent event = new MessageReceivedEvent(
                1L, "test-channel", channelId, "t1",
                MessageType.EVENT, "agent-1", null, Instant.now(), null, null);

        CloudEvent ce = CloudEventMapper.toCloudEvent(event, mapper);

        assertThat(ce.getType()).isEqualTo("io.casehub.qhorus.message.event");
        assertThat(ce.getData()).isNotNull();
    }

    @Test
    void usesMessageIdAsCloudEventId() {
        UUID channelId = UUID.randomUUID();
        MessageReceivedEvent event = new MessageReceivedEvent(
                42L, "test-channel", channelId, "t1",
                MessageType.STATUS, "agent-1", null, Instant.now(), "hello", null);

        CloudEvent ce = CloudEventMapper.toCloudEvent(event, mapper);

        assertThat(ce.getId()).isEqualTo("42");
    }

    @Test
    void fallsBackToRandomUuidWhenMessageIdNull() {
        UUID channelId = UUID.randomUUID();
        MessageReceivedEvent event = new MessageReceivedEvent(
                null, "test-channel", channelId, "t1",
                MessageType.STATUS, "agent-1", null, Instant.now(), "hello", null);

        CloudEvent ce = CloudEventMapper.toCloudEvent(event, mapper);

        assertThat(ce.getId()).isNotNull();
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> UUID.fromString(ce.getId()));
    }

}
