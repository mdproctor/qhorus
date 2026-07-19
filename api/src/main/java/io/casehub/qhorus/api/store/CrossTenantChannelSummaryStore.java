package io.casehub.qhorus.api.store;

import io.casehub.qhorus.api.channel.ChannelSummary;

import java.util.List;

public interface CrossTenantChannelSummaryStore {

    List<ChannelSummary> findAll();

    List<ChannelSummary> findWithAutoUpdateConfigured();
}
