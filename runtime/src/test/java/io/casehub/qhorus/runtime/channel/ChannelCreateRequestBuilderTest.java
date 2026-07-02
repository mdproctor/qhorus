package io.casehub.qhorus.runtime.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;

import org.junit.jupiter.api.Test;

import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.casehub.qhorus.api.message.MessageType;

class ChannelCreateRequestBuilderTest {

    @Test
    void builder_minimalArgs_defaultsSemanticToAppend() {
        io.casehub.qhorus.api.channel.ChannelCreateRequest req = io.casehub.qhorus.api.channel.ChannelCreateRequest.builder("test-ch").build();
        assertThat(req.name()).isEqualTo("test-ch");
        assertThat(req.semantic()).isEqualTo(ChannelSemantic.APPEND);
        assertThat(req.description()).isNull();
        assertThat(req.allowedTypes()).isNull();
        assertThat(req.deniedTypes()).isNull();
        assertThat(req.hasConnectorBinding()).isFalse();
    }

    @Test
    void builder_allFields_roundTrips() {
        io.casehub.qhorus.api.channel.ChannelCreateRequest req = io.casehub.qhorus.api.channel.ChannelCreateRequest.builder("full-ch")
                                                                                                                   .description("Full channel")
                                                                                                                   .semantic(ChannelSemantic.BARRIER)
                                                                                                                   .barrierContributors("alice,bob")
                                                                                                                   .allowedWriters("alice")
                                                                                                                   .adminInstances("admin-1")
                                                                                                                   .rateLimitPerChannel(100)
                                                                                                                   .rateLimitPerInstance(10)
                                                                                                                   .allowedTypes(Set.of(MessageType.QUERY, MessageType.COMMAND))
                                                                                                                   .deniedTypes(Set.of(MessageType.EVENT))
                                                                                                                   .inboundConnectorId("slack-in")
                                                                                                                   .externalKey("C123")
                                                                                                                   .outboundConnectorId("slack-out")
                                                                                                                   .outboundDestination("#general")
                                                                                                                   .build();

        assertThat(req.name()).isEqualTo("full-ch");
        assertThat(req.description()).isEqualTo("Full channel");
        assertThat(req.semantic()).isEqualTo(ChannelSemantic.BARRIER);
        assertThat(req.barrierContributors()).isEqualTo("alice,bob");
        assertThat(req.allowedWriters()).isEqualTo("alice");
        assertThat(req.adminInstances()).isEqualTo("admin-1");
        assertThat(req.rateLimitPerChannel()).isEqualTo(100);
        assertThat(req.rateLimitPerInstance()).isEqualTo(10);
        assertThat(req.allowedTypes()).containsExactlyInAnyOrder(MessageType.QUERY, MessageType.COMMAND);
        assertThat(req.deniedTypes()).containsExactly(MessageType.EVENT);
        assertThat(req.hasConnectorBinding()).isTrue();
        assertThat(req.inboundConnectorId()).isEqualTo("slack-in");
        assertThat(req.externalKey()).isEqualTo("C123");
        assertThat(req.outboundConnectorId()).isEqualTo("slack-out");
        assertThat(req.outboundDestination()).isEqualTo("#general");
    }

    @Test
    void builder_overlappingTypes_throwsViaCompactConstructor() {
        assertThatThrownBy(() -> io.casehub.qhorus.api.channel.ChannelCreateRequest.builder("overlap-ch")
                                                                                   .allowedTypes(Set.of(MessageType.QUERY))
                                                                                   .deniedTypes(Set.of(MessageType.QUERY))
                                                                                   .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("intersect");
    }

    @Test
    void builder_partialConnectorBinding_throwsViaCompactConstructor() {
        assertThatThrownBy(() -> io.casehub.qhorus.api.channel.ChannelCreateRequest.builder("partial-bind")
                                                                                   .inboundConnectorId("slack-in")
                                                                                   .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Connector binding requires all four");
    }

    @Test
    void builder_invalidSlug_throwsViaCompactConstructor() {
        assertThatThrownBy(() -> io.casehub.qhorus.api.channel.ChannelCreateRequest.builder("INVALID").build())
                .isInstanceOf(IllegalArgumentException.class);
    }
}
