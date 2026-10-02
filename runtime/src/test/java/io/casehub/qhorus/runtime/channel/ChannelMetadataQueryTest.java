package io.casehub.qhorus.runtime.channel;

import io.casehub.qhorus.api.channel.Channel;
import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.casehub.qhorus.api.store.query.ChannelQuery;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ChannelMetadataQueryTest {

    @Test
    void byMetadata_matchesChannelWithKey() {
        Channel ch = Channel.builder("test").id(UUID.randomUUID()).semantic(ChannelSemantic.APPEND)
                .metadata(Map.of("project", "qhorus", "family", "casehub")).build();
        ChannelQuery q = ChannelQuery.byMetadata("project", "qhorus");
        assertThat(q.matches(ch)).isTrue();
    }

    @Test
    void byMetadata_rejectsChannelWithWrongValue() {
        Channel ch = Channel.builder("test").id(UUID.randomUUID()).semantic(ChannelSemantic.APPEND)
                .metadata(Map.of("project", "claudony")).build();
        ChannelQuery q = ChannelQuery.byMetadata("project", "qhorus");
        assertThat(q.matches(ch)).isFalse();
    }

    @Test
    void byMetadata_rejectsChannelWithNoMetadata() {
        Channel ch = Channel.builder("test").id(UUID.randomUUID()).semantic(ChannelSemantic.APPEND).build();
        ChannelQuery q = ChannelQuery.byMetadata("project", "qhorus");
        assertThat(q.matches(ch)).isFalse();
    }

    @Test
    void byMetadata_rejectsChannelMissingKey() {
        Channel ch = Channel.builder("test").id(UUID.randomUUID()).semantic(ChannelSemantic.APPEND)
                .metadata(Map.of("family", "casehub")).build();
        ChannelQuery q = ChannelQuery.byMetadata("project", "qhorus");
        assertThat(q.matches(ch)).isFalse();
    }

    @Test
    void allQuery_matchesChannelWithMetadata() {
        Channel ch = Channel.builder("test").id(UUID.randomUUID()).semantic(ChannelSemantic.APPEND)
                .metadata(Map.of("project", "qhorus")).build();
        assertThat(ChannelQuery.all().matches(ch)).isTrue();
    }
}
