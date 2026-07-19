package io.casehub.qhorus.runtime.store.jpa;

import io.casehub.qhorus.runtime.channel.ChannelSummaryEntity;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.UUID;

@ApplicationScoped
class ChannelSummaryPanacheRepo implements PanacheRepositoryBase<ChannelSummaryEntity, UUID> {
}
