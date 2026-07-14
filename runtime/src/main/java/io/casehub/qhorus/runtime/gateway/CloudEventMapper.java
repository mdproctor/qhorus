package io.casehub.qhorus.runtime.gateway;

import java.net.URI;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.jboss.logging.Logger;

import io.casehub.qhorus.api.gateway.MessageReceivedEvent;
import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;

public final class CloudEventMapper {

    private static final Logger LOG = Logger.getLogger(CloudEventMapper.class);

    private CloudEventMapper() {}

    public static CloudEvent toCloudEvent(MessageReceivedEvent event, ObjectMapper objectMapper) {
        String type = "io.casehub.qhorus.message." + event.messageType().name().toLowerCase(Locale.ROOT);
        URI source = URI.create("/casehub-qhorus/channel/" + event.channelId());

        byte[] data;
        try {
            data = objectMapper.writeValueAsBytes(event);
        } catch (JsonProcessingException e) {
            LOG.warnf(e, "Failed to serialise MessageReceivedEvent for CloudEvent — channel=%s type=%s",
                    event.channelId(), event.messageType());
            data = new byte[0];
        }

        CloudEventBuilder builder = CloudEventBuilder.v1()
                .withId(event.messageId() != null
                        ? String.valueOf(event.messageId())
                        : UUID.randomUUID().toString())
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
