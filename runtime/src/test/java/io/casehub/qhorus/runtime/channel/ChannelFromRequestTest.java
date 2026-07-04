package io.casehub.qhorus.runtime.channel;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.casehub.qhorus.api.message.MessageType;

class ChannelFromRequestTest {

    @Test
    void fromRequest_mapsAllFields() {
        io.casehub.qhorus.api.channel.ChannelCreateRequest req = io.casehub.qhorus.api.channel.ChannelCreateRequest.builder("from-req-ch")
                                                                                                                   .description("Test channel")
                                                                                                                   .semantic(ChannelSemantic.BARRIER)
                                                                                                                   .barrierContributors(List.of("alice", "bob"))
                                                                                                                   .allowedWriters(List.of("alice"))
                                                                                                                   .adminInstances(List.of("admin-1"))
                                                                                                                   .rateLimitPerChannel(100)
                                                                                                                   .rateLimitPerInstance(10)
                                                                                                                   .allowedTypes(Set.of(MessageType.QUERY, MessageType.COMMAND))
                                                                                                                   .deniedTypes(Set.of(MessageType.EVENT))
                                                                                                                   .build();

        ChannelEntity ch = ChannelEntity.fromDomain(io.casehub.qhorus.api.channel.Channel.fromRequest(req, "tenant-42"));

        assertThat(ch.name).isEqualTo("from-req-ch");
        assertThat(ch.description).isEqualTo("Test channel");
        assertThat(ch.semantic).isEqualTo(ChannelSemantic.BARRIER);
        assertThat(ch.barrierContributors).isEqualTo("alice,bob");
        assertThat(ch.allowedWriters).isEqualTo("alice");
        assertThat(ch.adminInstances).isEqualTo("admin-1");  // joinCsv(List.of("admin-1")) = "admin-1"
        assertThat(ch.rateLimitPerChannel).isEqualTo(100);
        assertThat(ch.rateLimitPerInstance).isEqualTo(10);
        assertThat(ch.allowedTypes).isEqualTo("COMMAND,QUERY");
        assertThat(ch.deniedTypes).isEqualTo("EVENT");
        assertThat(ch.tenancyId).isEqualTo("tenant-42");
    }

    @Test
    void fromRequest_unsetWritersProduceNullEntityField() {
        io.casehub.qhorus.api.channel.ChannelCreateRequest req = io.casehub.qhorus.api.channel.ChannelCreateRequest.builder("blank-ch")
                                                                                                                   .build();

        ChannelEntity ch = ChannelEntity.fromDomain(io.casehub.qhorus.api.channel.Channel.fromRequest(req, "t1"));

        assertThat(ch.allowedWriters).isNull();
        assertThat(ch.adminInstances).isNull();
    }

    @Test
    void fromRequest_nullTypesSerialiseToNull() {
        io.casehub.qhorus.api.channel.ChannelCreateRequest req = io.casehub.qhorus.api.channel.ChannelCreateRequest.builder("null-types-ch").build();
        ChannelEntity                                      ch  = ChannelEntity.fromDomain(io.casehub.qhorus.api.channel.Channel.fromRequest(req, "t1"));

        assertThat(ch.allowedTypes).isNull();
        assertThat(ch.deniedTypes).isNull();
    }
}
