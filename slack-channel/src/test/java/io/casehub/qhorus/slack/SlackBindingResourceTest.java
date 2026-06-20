package io.casehub.qhorus.slack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import jakarta.ws.rs.core.Response;

import org.eclipse.microprofile.config.Config;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.casehub.qhorus.runtime.channel.Channel;
import io.casehub.qhorus.runtime.channel.ChannelConnectorBinding;
import io.casehub.qhorus.runtime.channel.ChannelService;
import io.casehub.qhorus.runtime.gateway.ChannelGateway;
import io.casehub.qhorus.runtime.store.ChannelBindingStore;

class SlackBindingResourceTest {

    private SlackBotBindingStore bindingStore;
    private ChannelService channelService;
    private ChannelGateway gateway;
    private SlackChannelBackend backend;
    private ChannelBindingStore channelBindingStore;
    private SlackThreadCacheStore threadCacheStore;
    private Config config;
    private SlackBindingResource resource;

    private final UUID channelId = UUID.randomUUID();
    private final String workspaceId = "T123ABC";
    private final String slackChannelId = "C123ABC";
    private final String credKey = "casehub.qhorus.slack-channel.credentials." + workspaceId;

    @BeforeEach
    void setUp() {
        bindingStore = mock(SlackBotBindingStore.class);
        channelService = mock(ChannelService.class);
        gateway = mock(ChannelGateway.class);
        backend = mock(SlackChannelBackend.class);
        channelBindingStore = mock(ChannelBindingStore.class);
        threadCacheStore = mock(SlackThreadCacheStore.class);
        config = mock(Config.class);

        resource = new SlackBindingResource(
                bindingStore, channelService, gateway, backend,
                channelBindingStore, threadCacheStore, config);

        // Default: channel exists, no conflict, valid token
        Channel ch = new Channel();
        ch.id = channelId;
        ch.name = "test-channel";
        when(channelService.findById(channelId)).thenReturn(Optional.of(ch));
        when(channelBindingStore.findByChannelId(channelId)).thenReturn(Optional.empty());
        when(config.getValue(credKey, String.class)).thenReturn("xoxb-valid");
    }

    @Test
    void put_channelNotFound_returns404_beforeSave() {
        when(channelService.findById(channelId)).thenReturn(Optional.empty());
        Response r = resource.put(channelId, new SlackBindingRequest(slackChannelId, workspaceId));
        assertThat(r.getStatus()).isEqualTo(404);
        verify(bindingStore, never()).save(any());
    }

    @Test
    void put_channelConnectorBindingExists_returns409_beforeSave() {
        when(channelBindingStore.findByChannelId(channelId))
                .thenReturn(Optional.of(new ChannelConnectorBinding()));
        Response r = resource.put(channelId, new SlackBindingRequest(slackChannelId, workspaceId));
        assertThat(r.getStatus()).isEqualTo(409);
        verify(bindingStore, never()).save(any());
    }

    @Test
    void put_missingCredential_returns400_beforeSave() {
        when(config.getValue(credKey, String.class)).thenThrow(new NoSuchElementException());
        Response r = resource.put(channelId, new SlackBindingRequest(slackChannelId, workspaceId));
        assertThat(r.getStatus()).isEqualTo(400);
        verify(bindingStore, never()).save(any());
    }

    @Test
    void put_blankCredential_returns400_beforeSave() {
        when(config.getValue(credKey, String.class)).thenReturn("");
        Response r = resource.put(channelId, new SlackBindingRequest(slackChannelId, workspaceId));
        assertThat(r.getStatus()).isEqualTo(400);
        verify(bindingStore, never()).save(any());
    }

    @Test
    void put_validRequest_evictsBeforeSave() {
        Response r = resource.put(channelId, new SlackBindingRequest(slackChannelId, workspaceId));
        assertThat(r.getStatus()).isEqualTo(200);
        var inOrder = inOrder(backend, threadCacheStore, bindingStore);
        inOrder.verify(backend).evict(channelId);
        inOrder.verify(threadCacheStore).deleteAllByChannelId(channelId);
        inOrder.verify(bindingStore).save(any(SlackBotBinding.class));
    }

    @Test
    void delete_returns204() {
        Response r = resource.delete(channelId);
        assertThat(r.getStatus()).isEqualTo(204);
    }
}
