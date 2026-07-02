package io.casehub.qhorus.runtime.conversion;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.channel.Channel;
import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.casehub.qhorus.api.message.Commitment;
import io.casehub.qhorus.api.message.CommitmentState;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.channel.ChannelEntity;
import io.casehub.qhorus.runtime.message.CommitmentEntity;
import io.casehub.qhorus.runtime.message.MessageEntity;

class EntityConversionTest {

    @Test
    void channel_roundTrip() {
        Channel original = Channel.builder("test-channel")
                .id(UUID.randomUUID())
                .description("A test channel")
                .semantic(ChannelSemantic.APPEND)
                .barrierContributors(List.of("a", "b"))
                .allowedWriters(List.of("w1"))
                .adminInstances(List.of("admin"))
                .rateLimitPerChannel(50)
                .rateLimitPerInstance(5)
                .allowedTypes(Set.of(MessageType.COMMAND))
                .deniedTypes(Set.of(MessageType.EVENT))
                .paused(true)
                .autoCreated(false)
                .tenancyId("t1")
                .createdAt(Instant.parse("2026-01-01T00:00:00Z"))
                .lastActivityAt(Instant.parse("2026-06-30T12:00:00Z"))
                .build();

        ChannelEntity entity = ChannelEntity.fromDomain(original);
        Channel roundTripped = entity.toDomain();

        assertThat(roundTripped).isEqualTo(original);
    }

    @Test
    void channel_nullCollections_roundTrip() {
        Channel original = Channel.builder("minimal")
                .id(UUID.randomUUID())
                .semantic(ChannelSemantic.APPEND)
                .tenancyId("t")
                .createdAt(Instant.now())
                .lastActivityAt(Instant.now())
                .build();

        Channel roundTripped = ChannelEntity.fromDomain(original).toDomain();

        assertThat(roundTripped).isEqualTo(original);
        assertThat(roundTripped.barrierContributors()).isNull();
        assertThat(roundTripped.allowedWriters()).isNull();
        assertThat(roundTripped.adminInstances()).isNull();
        assertThat(roundTripped.allowedTypes()).isNull();
        assertThat(roundTripped.deniedTypes()).isNull();
    }

    @Test
    void message_roundTrip() {
        io.casehub.qhorus.api.message.Message original = io.casehub.qhorus.api.message.Message.builder()
                .id(42L)
                .channelId(UUID.randomUUID())
                .sender("agent-1")
                .messageType(MessageType.COMMAND)
                .actorType(ActorType.AGENT)
                .tenancyId("t1")
                .content("do the thing")
                .correlationId("corr-1")
                .inReplyTo(null)
                .replyCount(0)
                .artefactRefs(List.of(UUID.randomUUID(), UUID.randomUUID()))
                .target("role:specialist")
                .commitmentId(UUID.randomUUID())
                .deadline(Instant.parse("2026-06-30T13:00:00Z"))
                .version(1)
                .createdAt(Instant.parse("2026-06-30T12:00:00Z"))
                .build();

        MessageEntity entity = MessageEntity.fromDomain(original);
        io.casehub.qhorus.api.message.Message roundTripped = entity.toDomain();

        assertThat(roundTripped).isEqualTo(original);
    }

    @Test
    void message_nullArtefactRefs_roundTrip() {
        io.casehub.qhorus.api.message.Message original = io.casehub.qhorus.api.message.Message.builder()
                .id(1L)
                .channelId(UUID.randomUUID())
                .sender("agent-1")
                .messageType(MessageType.STATUS)
                .actorType(ActorType.AGENT)
                .tenancyId("t")
                .createdAt(Instant.now())
                .build();

        io.casehub.qhorus.api.message.Message roundTripped = MessageEntity.fromDomain(original).toDomain();

        assertThat(roundTripped).isEqualTo(original);
        assertThat(roundTripped.artefactRefs()).isNull();
    }

    @Test
    void commitment_roundTrip() {
        Commitment original = Commitment.builder()
                .id(UUID.randomUUID())
                .correlationId("corr-1")
                .channelId(UUID.randomUUID())
                .messageType(MessageType.COMMAND)
                .requester("agent-1")
                .obligor("agent-2")
                .state(CommitmentState.OPEN)
                .expiresAt(Instant.parse("2026-06-30T13:00:00Z"))
                .tenancyId("t1")
                .createdAt(Instant.parse("2026-06-30T12:00:00Z"))
                .build();

        CommitmentEntity entity = CommitmentEntity.fromDomain(original);
        Commitment roundTripped = entity.toDomain();

        assertThat(roundTripped).isEqualTo(original);
    }

    @Test
    void commitment_nullOptionalFields_roundTrip() {
        Commitment original = Commitment.builder()
                .id(UUID.randomUUID())
                .correlationId("corr-2")
                .channelId(UUID.randomUUID())
                .messageType(MessageType.QUERY)
                .requester("agent-1")
                .state(CommitmentState.OPEN)
                .tenancyId("t")
                .createdAt(Instant.now())
                .build();

        Commitment roundTripped = CommitmentEntity.fromDomain(original).toDomain();

        assertThat(roundTripped).isEqualTo(original);
        assertThat(roundTripped.obligor()).isNull();
        assertThat(roundTripped.expiresAt()).isNull();
        assertThat(roundTripped.acknowledgedAt()).isNull();
        assertThat(roundTripped.resolvedAt()).isNull();
        assertThat(roundTripped.delegatedTo()).isNull();
        assertThat(roundTripped.parentCommitmentId()).isNull();
    }
}
