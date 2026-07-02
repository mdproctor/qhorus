package io.casehub.qhorus.slack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import jakarta.ws.rs.core.Response;

import io.casehub.platform.api.credentials.CredentialPropertyKeys;
import io.casehub.platform.api.credentials.CredentialResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.casehub.qhorus.api.channel.Channel;
import io.casehub.qhorus.api.channel.ChannelConnectorBinding;
import io.casehub.qhorus.runtime.channel.ChannelService;
import io.casehub.qhorus.runtime.gateway.ChannelGateway;
import io.casehub.qhorus.api.store.ChannelBindingStore;

class SlackBindingResourceTest {

    private SlackBotBindingStore bindingStore;
    private ChannelService channelService;
    private ChannelGateway gateway;
    private SlackChannelBackend backend;
    private ChannelBindingStore channelBindingStore;
    private SlackThreadCacheStore threadCacheStore;
    private CredentialResolver credentialResolver;
    private SlackBindingResource resource;

    private final UUID channelId = UUID.randomUUID();
    private final String workspaceId = "T123ABC";
    private final String slackChannelId = "C123ABC";

    @BeforeEach
    void setUp() {
        bindingStore = mock(SlackBotBindingStore.class);
        channelService = mock(ChannelService.class);
        gateway = mock(ChannelGateway.class);
        backend = mock(SlackChannelBackend.class);
        channelBindingStore = mock(ChannelBindingStore.class);
        threadCacheStore = mock(SlackThreadCacheStore.class);
        credentialResolver = mock(CredentialResolver.class);

        resource = new SlackBindingResource(
                bindingStore, channelService, gateway, backend,
                channelBindingStore, threadCacheStore, credentialResolver);

        // Default: channel exists, no conflict, valid token
        Channel ch = Channel.builder("test-channel").id(channelId).build();
        when(channelService.findById(channelId)).thenReturn(Optional.of(ch));
        when(channelBindingStore.findByChannelId(channelId)).thenReturn(Optional.empty());
        when(credentialResolver.resolve(workspaceId))
                .thenReturn(Map.of(CredentialPropertyKeys.BEARER_TOKEN, "xoxb-valid"));
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
                .thenReturn(Optional.of(new ChannelConnectorBinding(channelId, null, null, null, null)));
        Response r = resource.put(channelId, new SlackBindingRequest(slackChannelId, workspaceId));
        assertThat(r.getStatus()).isEqualTo(409);
        verify(bindingStore, never()).save(any());
    }

    @Test
    void put_missingCredential_returns400_beforeSave() {
        when(credentialResolver.resolve(workspaceId)).thenReturn(Map.of());
        Response r = resource.put(channelId, new SlackBindingRequest(slackChannelId, workspaceId));
        assertThat(r.getStatus()).isEqualTo(400);
        verify(bindingStore, never()).save(any());
    }

    @Test
    void put_blankCredential_returns400_beforeSave() {
        when(credentialResolver.resolve(workspaceId))
                .thenReturn(Map.of(CredentialPropertyKeys.BEARER_TOKEN, ""));
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
