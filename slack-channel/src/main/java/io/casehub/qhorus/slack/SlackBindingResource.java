package io.casehub.qhorus.slack;

import java.time.Instant;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import io.casehub.platform.api.credentials.CredentialPropertyKeys;
import io.casehub.platform.api.credentials.CredentialResolver;
import io.casehub.qhorus.api.gateway.ChannelRef;
import io.casehub.qhorus.runtime.channel.ChannelService;
import io.casehub.qhorus.runtime.gateway.ChannelGateway;
import io.casehub.qhorus.runtime.gateway.DuplicateParticipatingBackendException;
import io.casehub.qhorus.api.store.ChannelBindingStore;

/**
 * Manages Slack bot bindings — associates a Qhorus channel with a Slack channel.
 *
 * <p>No auth annotations — consistent with all other qhorus REST resources.
 * Network isolation is the current security boundary.
 *
 * <p>put() is intentionally NOT @Transactional — see spec Known Limitations.
 * Order of checks: channel-exists → binding-conflict → credential-valid → evict → save → initChannel.
 */
@Path("/slack-channel/bindings")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@ApplicationScoped
public class SlackBindingResource {

    private final SlackBotBindingStore bindingStore;
    private final ChannelService channelService;
    private final ChannelGateway gateway;
    private final SlackChannelBackend backend;
    private final ChannelBindingStore channelBindingStore;
    private final SlackThreadCacheStore threadCacheStore;
    private final CredentialResolver credentialResolver;

    public SlackBindingResource(SlackBotBindingStore bindingStore,
                                ChannelService channelService,
                                ChannelGateway gateway,
                                SlackChannelBackend backend,
                                ChannelBindingStore channelBindingStore,
                                SlackThreadCacheStore threadCacheStore,
                                CredentialResolver credentialResolver) {
        this.bindingStore = bindingStore;
        this.channelService = channelService;
        this.gateway = gateway;
        this.backend = backend;
        this.channelBindingStore = channelBindingStore;
        this.threadCacheStore = threadCacheStore;
        this.credentialResolver = credentialResolver;
    }

    /**
     * Creates or replaces a Slack binding.
     *
     * <p>Returns:
     * <ul>
     *   <li>404 if the Qhorus channel does not exist
     *   <li>409 if the channel already has a generic ChannelConnectorBinding
     *   <li>400 if the workspaceId credential is not configured or is blank
     *   <li>200 with the binding DTO on success
     * </ul>
     */
    @PUT
    @Path("/{channelId}")
    public Response put(@PathParam("channelId") UUID channelId, SlackBindingRequest req) {
        // 1. Channel must exist
        var channel = channelService.findById(channelId).orElse(null);
        if (channel == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("Channel not found: " + channelId).build();
        }

        // 2. Mutual exclusion: reject if a generic ChannelConnectorBinding already occupies this channel
        if (channelBindingStore.findByChannelId(channelId).isPresent()) {
            return Response.status(Response.Status.CONFLICT)
                    .entity("Channel already has a generic connector binding").build();
        }

        // 3. Credential must exist and be non-blank
        var creds = credentialResolver.resolve(req.workspaceId());
        String token = creds.get(CredentialPropertyKeys.BEARER_TOKEN);
        if (token == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("Missing credential: casehub.credentials." + req.workspaceId()).build();
        }
        if (token.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("Credential casehub.credentials." + req.workspaceId() + " is configured but blank").build();
        }

        // 4. Clean stale state — safe no-op for fresh binds; handles the rebind case
        backend.evict(channelId);
        threadCacheStore.deleteAllByChannelId(channelId);

        // 5. Persist
        SlackBotBinding binding = new SlackBotBinding();
        binding.channelId = channelId;
        binding.slackChannelId = req.slackChannelId();
        binding.workspaceId = req.workspaceId();
        binding.createdAt = Instant.now();
        bindingStore.save(binding);

        // 6. Register backend — catch race between step 2 and here
        try {
            gateway.initChannel(channelId, new ChannelRef(channelId, channel.name()));
        } catch (DuplicateParticipatingBackendException e) {
            bindingStore.deleteByChannelId(channelId);
            return Response.status(Response.Status.CONFLICT)
                    .entity("Channel already has a participating backend: " + e.getMessage()).build();
        }

        return Response.ok(SlackBindingDto.from(channelId, binding)).build();
    }

    /** Returns the binding for the given channel. Token is never included. */
    @GET
    @Path("/{channelId}")
    public SlackBindingDto get(@PathParam("channelId") UUID channelId) {
        return bindingStore.findByChannelId(channelId)
                .map(b -> SlackBindingDto.from(channelId, b))
                .orElseThrow(NotFoundException::new);
    }

    /**
     * Removes the binding and evicts in-memory state. DB thread cache rows are also deleted
     * on unbind to avoid stale data on rebind.
     */
    @DELETE
    @Path("/{channelId}")
    public Response delete(@PathParam("channelId") UUID channelId) {
        backend.evict(channelId);
        gateway.deregisterBackend(channelId, SlackChannelBackend.BACKEND_ID);
        bindingStore.deleteByChannelId(channelId);
        return Response.noContent().build();
    }
}
