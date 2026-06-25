package io.casehub.qhorus.runtime.gateway;

import java.net.URI;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.casehub.qhorus.api.gateway.MessageReceivedEvent;
import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;

/**
 * CDI adapter that bridges {@link MessageReceivedEvent} to the CloudEvents ecosystem.
 *
 * <p>Observes every {@link MessageReceivedEvent} fired by the Qhorus dispatch path
 * and re-fires it as a {@link CloudEvent} via {@code Event<CloudEvent>.fireAsync()}.
 * Any CDI bean in the application can observe {@code @ObservesAsync CloudEvent}
 * to receive Qhorus channel messages without a compile-time dependency on Qhorus types.
 *
 * <p>Mapping:<br>
 * {@code type}      → {@code io.casehub.qhorus.message.<messageType>} (lowercase)<br>
 * {@code source}    → {@code /casehub-qhorus/channel/<channelId>}<br>
 * {@code subject}   → {@code channel/<channelId>}<br>
 * {@code id}        → random UUID per event<br>
 * {@code data}      → JSON-serialised {@link MessageReceivedEvent}<br>
 * {@code tenancyid} → extension carrying {@link MessageReceivedEvent#tenancyId()};
 *                     omitted when {@code tenancyId} is null<br>
 *
 * <p>{@code tenancyId} is sourced directly from the event record, not from
 * {@code CurrentPrincipal}, which may not be request-scoped in async observers.
 */
@ApplicationScoped
public class QhorusCloudEventAdapter {

    private static final Logger LOG = Logger.getLogger(QhorusCloudEventAdapter.class);

    private final Event<CloudEvent> cloudEventBus;
    private final ObjectMapper objectMapper;

    @Inject
    public QhorusCloudEventAdapter(Event<CloudEvent> cloudEventBus, ObjectMapper objectMapper) {
        this.cloudEventBus = cloudEventBus;
        this.objectMapper = objectMapper;
    }

    public void onMessageReceived(@ObservesAsync MessageReceivedEvent event) {
        cloudEventBus.fireAsync(toCloudEvent(event))
                .exceptionally(ex -> {
                    LOG.warnf(ex, "CloudEvent dispatch failed for channel=%s type=%s",
                            event.channelId(), event.messageType());
                    return null;
                });
    }

    private CloudEvent toCloudEvent(MessageReceivedEvent event) {
        String type = "io.casehub.qhorus.message." + event.messageType().name().toLowerCase(Locale.ROOT);
        URI source = URI.create("/casehub-qhorus/channel/" + event.channelId());

        byte[] data;
        try {
            data = objectMapper.writeValueAsBytes(event);
        } catch (JsonProcessingException e) {
            LOG.warnf(e, "Failed to serialise MessageReceivedEvent for CloudEvent data — channel=%s type=%s",
                    event.channelId(), event.messageType());
            data = new byte[0];
        }

        CloudEventBuilder builder = CloudEventBuilder.v1()
                .withId(UUID.randomUUID().toString())
                .withType(type)
                .withSource(source)
                .withSubject("channel/" + event.channelId())
                .withTime(event.occurredAt().atOffset(ZoneOffset.UTC))
                .withDataContentType("application/json")
                .withData(data);

        if (event.tenancyId() != null) {
            builder = builder.withExtension("tenancyid", event.tenancyId());
        }

        return builder.build();
    }
}
