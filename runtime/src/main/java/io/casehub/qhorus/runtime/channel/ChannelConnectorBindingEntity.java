package io.casehub.qhorus.runtime.channel;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;

@Entity
@Table(name = "channel_connector_binding",
       uniqueConstraints = @UniqueConstraint(name = "uq_binding_key",
           columnNames = {"inbound_connector_id", "external_key"}))
public class ChannelConnectorBindingEntity extends PanacheEntityBase {

    public static ChannelConnectorBindingEntity fromDomain(io.casehub.qhorus.api.channel.ChannelConnectorBinding b) {
        ChannelConnectorBindingEntity e = new ChannelConnectorBindingEntity();
        e.channelId = b.channelId();
        e.inboundConnectorId = b.inboundConnectorId();
        e.externalKey = b.externalKey();
        e.outboundConnectorId = b.outboundConnectorId();
        e.outboundDestination = b.outboundDestination();
        return e;
    }

    public io.casehub.qhorus.api.channel.ChannelConnectorBinding toDomain() {
        return new io.casehub.qhorus.api.channel.ChannelConnectorBinding(
                channelId, inboundConnectorId, externalKey, outboundConnectorId, outboundDestination);
    }

    @Id
    @Column(name = "channel_id", nullable = false)
    public UUID channelId;

    @Column(name = "inbound_connector_id", nullable = false, length = 64)
    public String inboundConnectorId;

    @Column(name = "external_key", nullable = false, length = 255)
    public String externalKey;

    @Column(name = "outbound_connector_id", nullable = false, length = 64)
    public String outboundConnectorId;

    @Column(name = "outbound_destination", nullable = false, length = 512)
    public String outboundDestination;
}
