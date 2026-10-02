package io.casehub.qhorus.runtime.channel;

import io.casehub.qhorus.api.channel.Channel;
import io.casehub.qhorus.api.channel.ChannelSemantic;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ChannelMetadataTest {

    @Test
    void channelRecord_metadataDefensiveCopy() {
        var mutable = new LinkedHashMap<String, String>();
        mutable.put("key", "val");
        Channel ch = Channel.builder("test").id(UUID.randomUUID()).semantic(ChannelSemantic.APPEND)
                .metadata(mutable).build();
        mutable.put("key2", "val2");
        assertThat(ch.metadata()).doesNotContainKey("key2");
    }

    @Test
    void channelRecord_nullMetadataRemains() {
        Channel ch = Channel.builder("test").id(UUID.randomUUID()).semantic(ChannelSemantic.APPEND).build();
        assertThat(ch.metadata()).isNull();
    }

    @Test
    void channelEntity_roundTrip() {
        Channel ch = Channel.builder("test-meta").id(UUID.randomUUID()).semantic(ChannelSemantic.APPEND)
                .metadata(Map.of("project", "qhorus", "family", "casehub")).build();
        ChannelEntity entity = ChannelEntity.fromDomain(ch);
        assertThat(entity.metadata).isNotNull();
        Channel restored = entity.toDomain();
        assertThat(restored.metadata()).containsEntry("project", "qhorus");
        assertThat(restored.metadata()).containsEntry("family", "casehub");
    }

    @Test
    void channelEntity_nullMetadata_roundTrip() {
        Channel ch = Channel.builder("test-null").id(UUID.randomUUID()).semantic(ChannelSemantic.APPEND).build();
        ChannelEntity entity = ChannelEntity.fromDomain(ch);
        assertThat(entity.metadata).isNull();
        Channel restored = entity.toDomain();
        assertThat(restored.metadata()).isNull();
    }

    @Test
    void toBuilder_preservesMetadata() {
        Channel ch = Channel.builder("test-builder").id(UUID.randomUUID()).semantic(ChannelSemantic.APPEND)
                .metadata(Map.of("k", "v")).build();
        Channel copy = ch.toBuilder().build();
        assertThat(copy.metadata()).containsEntry("k", "v");
    }
}
