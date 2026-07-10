package io.casehub.qhorus.runtime.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import jakarta.enterprise.event.Event;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import io.casehub.qhorus.api.gateway.MessageReceivedEvent;
import io.casehub.qhorus.api.message.MessageType;
import io.cloudevents.CloudEvent;

/**
 * CDI-free unit tests for QhorusCloudEventAdapter.
 */
class QhorusCloudEventAdapterTest {

    private Event<CloudEvent> cloudEventBus;
    private QhorusCloudEventAdapter adapter;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        cloudEventBus = mock(Event.class);
        when(cloudEventBus.fireAsync(any(CloudEvent.class)))
                .thenReturn(CompletableFuture.completedFuture(null));
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        adapter = new QhorusCloudEventAdapter(cloudEventBus, mapper);
    }

    @Test
    void onMessageReceived_command_firesCloudEventWithCorrectType() {
        UUID channelId = UUID.randomUUID();
        MessageReceivedEvent event = new MessageReceivedEvent(
                "my-channel", channelId, "tenant-1", MessageType.COMMAND,
                "agent:alice", UUID.randomUUID().toString(), Instant.now(), "hello", null);

        adapter.onMessageReceived(event);

        CloudEvent ce = captureCloudEvent();
        assertThat(ce.getType()).isEqualTo("io.casehub.qhorus.message.command");
    }

    @Test
    void onMessageReceived_event_firesCloudEventWithEventType() {
        UUID channelId = UUID.randomUUID();
        MessageReceivedEvent event = new MessageReceivedEvent(
                "telemetry", channelId, "tenant-1", MessageType.EVENT,
                "system:normaliser", null, Instant.now(), null, null);

        adapter.onMessageReceived(event);

        CloudEvent ce = captureCloudEvent();
        assertThat(ce.getType()).isEqualTo("io.casehub.qhorus.message.event");
    }

    @Test
    void onMessageReceived_setsCorrectSubject() {
        UUID channelId = UUID.randomUUID();
        MessageReceivedEvent event = new MessageReceivedEvent(
                "ch", channelId, "t1", MessageType.RESPONSE,
                "agent:bob", UUID.randomUUID().toString(), Instant.now(), "answer", null);

        adapter.onMessageReceived(event);

        CloudEvent ce = captureCloudEvent();
        assertThat(ce.getSubject()).isEqualTo("channel/" + channelId);
    }

    @Test
    void onMessageReceived_setsCorrectSource() {
        UUID channelId = UUID.randomUUID();
        MessageReceivedEvent event = new MessageReceivedEvent(
                "ch", channelId, "t1", MessageType.STATUS,
                "agent:bob", null, Instant.now(), "working", null);

        adapter.onMessageReceived(event);

        CloudEvent ce = captureCloudEvent();
        assertThat(ce.getSource().toString()).isEqualTo("/casehub-qhorus/channel/" + channelId);
    }

    @Test
    void onMessageReceived_propagatesTenancyIdExtension() {
        UUID channelId = UUID.randomUUID();
        MessageReceivedEvent event = new MessageReceivedEvent(
                "ch", channelId, "acme-corp", MessageType.DONE,
                "agent:alice", UUID.randomUUID().toString(), Instant.now(), "done", null);

        adapter.onMessageReceived(event);

        CloudEvent ce = captureCloudEvent();
        assertThat(ce.getExtension("tenancyid")).isEqualTo("acme-corp");
    }

    @Test
    void onMessageReceived_nullTenancyId_omitsExtension() {
        UUID channelId = UUID.randomUUID();
        MessageReceivedEvent event = new MessageReceivedEvent(
                "ch", channelId, null, MessageType.QUERY,
                "agent:alice", UUID.randomUUID().toString(), Instant.now(), "what?", null);

        adapter.onMessageReceived(event);

        CloudEvent ce = captureCloudEvent();
        assertThat(ce.getExtension("tenancyid")).isNull();
    }

    @Test
    void onMessageReceived_hasNonNullId() {
        UUID channelId = UUID.randomUUID();
        MessageReceivedEvent event = new MessageReceivedEvent(
                "ch", channelId, "t1", MessageType.QUERY,
                "agent:alice", null, Instant.now(), "q?", null);

        adapter.onMessageReceived(event);

        CloudEvent ce = captureCloudEvent();
        assertThat(ce.getId()).isNotNull().isNotBlank();
    }

    @Test
    void onMessageReceived_dataContentTypeIsJson() {
        UUID channelId = UUID.randomUUID();
        MessageReceivedEvent event = new MessageReceivedEvent(
                "ch", channelId, "t1", MessageType.COMMAND,
                "agent:alice", UUID.randomUUID().toString(), Instant.now(), "go", null);

        adapter.onMessageReceived(event);

        CloudEvent ce = captureCloudEvent();
        assertThat(ce.getDataContentType()).isEqualTo("application/json");
    }

    @Test
    void onMessageReceived_usesOccurredAtForCloudEventTime() {
        UUID channelId = UUID.randomUUID();
        Instant fixedTime = Instant.parse("2026-01-15T10:30:00Z");
        MessageReceivedEvent event = new MessageReceivedEvent(
                "ch", channelId, "t1", MessageType.COMMAND,
                "agent:alice", UUID.randomUUID().toString(), fixedTime, "go", null);

        adapter.onMessageReceived(event);

        CloudEvent ce = captureCloudEvent();
        assertThat(ce.getTime()).isNotNull();
        assertThat(ce.getTime().toInstant()).isEqualTo(fixedTime);
    }

    private CloudEvent captureCloudEvent() {
        ArgumentCaptor<CloudEvent> captor = ArgumentCaptor.forClass(CloudEvent.class);
        verify(cloudEventBus).fireAsync(captor.capture());
        return captor.getValue();
    }
}
