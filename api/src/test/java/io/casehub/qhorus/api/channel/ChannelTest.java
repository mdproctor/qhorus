package io.casehub.qhorus.api.channel;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.casehub.qhorus.api.message.MessageType;

import static org.assertj.core.api.Assertions.assertThat;

class ChannelTest {

    @Test
    void builder_createsRecordWithAllFields() {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        Channel ch = Channel.builder("test-channel")
                .id(id)
                .description("A test channel")
                .semantic(ChannelSemantic.BARRIER)
                .barrierContributors(List.of("agent-a", "agent-b"))
                .allowedWriters(List.of("writer-1"))
                .adminInstances(List.of("admin-1"))
                .rateLimitPerChannel(100)
                .rateLimitPerInstance(10)
                .allowedTypes(Set.of(MessageType.COMMAND, MessageType.QUERY))
                .deniedTypes(Set.of(MessageType.EVENT))
                .paused(true)
                .autoCreated(true)
                .tenancyId("tenant-1")
                .createdAt(now)
                .lastActivityAt(now)
                .build();

        assertThat(ch.id()).isEqualTo(id);
        assertThat(ch.name()).isEqualTo("test-channel");
        assertThat(ch.description()).isEqualTo("A test channel");
        assertThat(ch.semantic()).isEqualTo(ChannelSemantic.BARRIER);
        assertThat(ch.barrierContributors()).containsExactly("agent-a", "agent-b");
        assertThat(ch.allowedWriters()).containsExactly("writer-1");
        assertThat(ch.adminInstances()).containsExactly("admin-1");
        assertThat(ch.rateLimitPerChannel()).isEqualTo(100);
        assertThat(ch.rateLimitPerInstance()).isEqualTo(10);
        assertThat(ch.allowedTypes()).containsExactlyInAnyOrder(MessageType.COMMAND, MessageType.QUERY);
        assertThat(ch.deniedTypes()).containsExactly(MessageType.EVENT);
        assertThat(ch.paused()).isTrue();
        assertThat(ch.autoCreated()).isTrue();
        assertThat(ch.tenancyId()).isEqualTo("tenant-1");
        assertThat(ch.createdAt()).isEqualTo(now);
        assertThat(ch.lastActivityAt()).isEqualTo(now);
    }

    @Test
    void nullCollections_preservedAsNull() {
        Channel ch = Channel.builder("open-channel")
                .semantic(ChannelSemantic.APPEND)
                .build();

        assertThat(ch.allowedWriters()).isNull();
        assertThat(ch.adminInstances()).isNull();
        assertThat(ch.barrierContributors()).isNull();
        assertThat(ch.allowedTypes()).isNull();
        assertThat(ch.deniedTypes()).isNull();
    }

    @Test
    void defensiveCopies_preventMutationAfterConstruction() {
        List<String> writers = new ArrayList<>(List.of("writer-1"));
        Set<MessageType> allowed = new HashSet<>(Set.of(MessageType.COMMAND));

        Channel ch = Channel.builder("guarded")
                .semantic(ChannelSemantic.APPEND)
                .allowedWriters(writers)
                .allowedTypes(allowed)
                .build();

        writers.add("writer-2");
        allowed.add(MessageType.QUERY);

        assertThat(ch.allowedWriters()).containsExactly("writer-1");
        assertThat(ch.allowedTypes()).containsExactly(MessageType.COMMAND);
    }

    @Test
    void fromRequest_generatesIdAndTimestamps() {
        ChannelCreateRequest req = ChannelCreateRequest.builder("from-req")
                .semantic(ChannelSemantic.COLLECT)
                .allowedTypes(Set.of(MessageType.EVENT))
                .build();

        Channel ch = Channel.fromRequest(req, "tenant-42");

        assertThat(ch.id()).isNotNull();
        assertThat(ch.name()).isEqualTo("from-req");
        assertThat(ch.semantic()).isEqualTo(ChannelSemantic.COLLECT);
        assertThat(ch.allowedTypes()).containsExactly(MessageType.EVENT);
        assertThat(ch.tenancyId()).isEqualTo("tenant-42");
        assertThat(ch.createdAt()).isNotNull();
        assertThat(ch.lastActivityAt()).isNotNull();
        assertThat(ch.paused()).isFalse();
        assertThat(ch.autoCreated()).isFalse();
    }

    @Test
    void toBuilder_roundTripsAllFields() {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        Channel original = Channel.builder("round-trip")
                .id(id)
                .description("desc")
                .semantic(ChannelSemantic.BARRIER)
                .barrierContributors(List.of("agent-a", "agent-b"))
                .allowedWriters(List.of("writer-1"))
                .adminInstances(List.of("admin-1"))
                .rateLimitPerChannel(100)
                .rateLimitPerInstance(10)
                .allowedTypes(Set.of(MessageType.COMMAND, MessageType.QUERY))
                .deniedTypes(Set.of(MessageType.EVENT))
                .paused(true)
                .autoCreated(true)
                .tenancyId("tenant-1")
                .createdAt(now)
                .lastActivityAt(now)
                .build();

        Channel copy = original.toBuilder().build();

        assertThat(copy.id()).isEqualTo(original.id());
        assertThat(copy.name()).isEqualTo(original.name());
        assertThat(copy.description()).isEqualTo(original.description());
        assertThat(copy.semantic()).isEqualTo(original.semantic());
        assertThat(copy.barrierContributors()).isEqualTo(original.barrierContributors());
        assertThat(copy.allowedWriters()).isEqualTo(original.allowedWriters());
        assertThat(copy.adminInstances()).isEqualTo(original.adminInstances());
        assertThat(copy.rateLimitPerChannel()).isEqualTo(original.rateLimitPerChannel());
        assertThat(copy.rateLimitPerInstance()).isEqualTo(original.rateLimitPerInstance());
        assertThat(copy.allowedTypes()).isEqualTo(original.allowedTypes());
        assertThat(copy.deniedTypes()).isEqualTo(original.deniedTypes());
        assertThat(copy.paused()).isEqualTo(original.paused());
        assertThat(copy.autoCreated()).isEqualTo(original.autoCreated());
        assertThat(copy.tenancyId()).isEqualTo(original.tenancyId());
        assertThat(copy.createdAt()).isEqualTo(original.createdAt());
        assertThat(copy.lastActivityAt()).isEqualTo(original.lastActivityAt());
    }

    @Test
    void fromRequest_defaultsSemanticToAppend() {
        ChannelCreateRequest req = ChannelCreateRequest.builder("defaulted").build();

        Channel ch = Channel.fromRequest(req, "t");

        assertThat(ch.semantic()).isEqualTo(ChannelSemantic.APPEND);
    }
}
