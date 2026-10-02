package io.casehub.qhorus.runtime.api;

import io.casehub.qhorus.runtime.api.core.ChannelCore;
import io.casehub.qhorus.runtime.api.core.ErrorResponse;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

@Path("/api/channels")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ChannelResource {

    @Inject ChannelCore core;

    @POST
    public Response create(final io.casehub.qhorus.runtime.api.core.CreateChannelRequest req) {
        return Response.status(Response.Status.CREATED).entity(core.create(req)).build();
    }

    @GET
    public List<ChannelResponse> list(
            @QueryParam("prefix") final String prefix,
            @QueryParam("spaceId") final UUID spaceId,
            @QueryParam("paused") final Boolean paused,
            @QueryParam("metadataKey") final String metadataKey,
            @QueryParam("metadataValue") final String metadataValue) {
        return core.list(prefix, spaceId, paused, metadataKey, metadataValue);
    }

    @GET
    @Path("/{id}")
    public ChannelResponse getById(@PathParam("id") final String id) {
        return core.getById(id);
    }

    @DELETE
    @Path("/{id}")
    public Response delete(@PathParam("id") final String id,
                           @QueryParam("force") @DefaultValue("false") final boolean force) {
        core.delete(id, force);
        return Response.noContent().build();
    }

    // -- Aggregation --

    @GET
    @Path("/feed")
    public List<Map<String, Object>> feed(@QueryParam("limit") @DefaultValue("50") int limit) {
        return core.feed(limit);
    }

    @GET
    @Path("/{id}/timeline")
    public Response timeline(@PathParam("id") final String id,
                             @QueryParam("after") Long after,
                             @QueryParam("limit") @DefaultValue("100") int limit) {
        return Response.ok(core.timeline(id, after, limit)).build();
    }

    // -- Reactions --

    @POST
    @Path("/{id}/messages/{messageId}/reactions")
    @Transactional
    public Response addReaction(@PathParam("id") final String id,
                                @PathParam("messageId") String messageId,
                                io.casehub.qhorus.runtime.api.core.ReactionRequest request) {
        core.addReaction(id, messageId, request);
        return Response.ok().build();
    }

    @DELETE
    @Path("/{id}/messages/{messageId}/reactions/{emoji}")
    @Transactional
    public Response removeReaction(@PathParam("id") final String id,
                                   @PathParam("messageId") String messageId,
                                   @PathParam("emoji") String emoji) {
        core.removeReaction(id, messageId, emoji);
        return Response.ok().build();
    }

    @GET
    @Path("/{id}/messages/{messageId}/reactions")
    public List<String> listReactions(@PathParam("id") final String id,
                                      @PathParam("messageId") String messageId) {
        return core.listReactions(id, messageId);
    }

    // -- Topics --

    @POST
    @Path("/{id}/topics")
    @Transactional
    public Response createTopic(@PathParam("id") final String id,
                                io.casehub.qhorus.runtime.api.core.CreateTopicRequest request) {
        return Response.ok(core.createTopic(id, request)).build();
    }

    @GET
    @Path("/{id}/topics")
    public Response listTopics(@PathParam("id") final String id) {
        return Response.ok(core.listTopics(id)).build();
    }

    @PUT
    @Path("/{id}/topics/{topicId}")
    @Transactional
    public Response updateTopic(@PathParam("id") final String id,
                                @PathParam("topicId") String topicId,
                                io.casehub.qhorus.runtime.api.core.UpdateTopicRequest request) {
        core.updateTopic(id, topicId, request);
        return Response.ok(Map.of("ok", true)).build();
    }

    @POST
    @Path("/{id}/topics/{topicId}/merge")
    @Transactional
    public Response mergeTopic(@PathParam("id") final String id,
                               @PathParam("topicId") String topicId,
                               io.casehub.qhorus.runtime.api.core.MergeTopicRequest request) {
        core.mergeTopic(id, topicId, request);
        return Response.ok(Map.of("ok", true)).build();
    }

    // -- Members --

    @GET
    @Path("/{id}/members")
    public Response listMembers(@PathParam("id") final String id) {
        return Response.ok(core.listMembers(id)).build();
    }

    @POST
    @Path("/{id}/members")
    @Transactional
    public Response addMember(@PathParam("id") final String id,
                              io.casehub.qhorus.runtime.api.core.AddMemberRequest request) {
        core.addMember(id, request);
        return Response.ok().build();
    }

    @DELETE
    @Path("/{id}/members/{memberId}")
    @Transactional
    public Response removeMember(@PathParam("id") final String id,
                                 @PathParam("memberId") String memberId) {
        core.removeMember(id, memberId);
        return Response.ok().build();
    }

    // -- Presence --

    @GET
    @Path("/{id}/presence")
    public Response listPresence(@PathParam("id") final String id) {
        return Response.ok(core.listPresence(id)).build();
    }

    // -- Commitments --

    @GET
    @Path("/{id}/commitments")
    public Response listCommitments(@PathParam("id") final String id) {
        return Response.ok(core.listCommitments(id)).build();
    }

    // -- Correlation --

    @GET
    @Path("/{id}/correlation/{correlationId}")
    public Response correlationChain(@PathParam("id") final String id,
                                     @PathParam("correlationId") String correlationId) {
        return Response.ok(core.correlationChain(id, correlationId)).build();
    }

    // -- Messages --

    @POST
    @Path("/{id}/messages")
    @Consumes(MediaType.APPLICATION_JSON)
    @Transactional
    public Response postMessage(@PathParam("id") final String id,
                                io.casehub.qhorus.runtime.api.core.MessagePostRequest request) {
        return Response.ok(core.postMessage(id, request)).build();
    }

    // -- Lifecycle --

    @POST
    @Path("/{id}/pause")
    @Consumes(MediaType.WILDCARD)
    public ChannelResponse pause(@PathParam("id") final String id) {
        return core.pause(id);
    }

    @POST
    @Path("/{id}/resume")
    @Consumes(MediaType.WILDCARD)
    public ChannelResponse resume(@PathParam("id") final String id) {
        return core.resume(id);
    }

    // -- Settings --

    @PUT
    @Path("/{id}/allowed-writers")
    public ChannelResponse setAllowedWriters(@PathParam("id") final String id,
                                              final io.casehub.qhorus.runtime.api.core.StringListRequest req) {
        return core.setAllowedWriters(id, req);
    }

    @PUT
    @Path("/{id}/admin-instances")
    public ChannelResponse setAdminInstances(@PathParam("id") final String id,
                                              final io.casehub.qhorus.runtime.api.core.StringListRequest req) {
        return core.setAdminInstances(id, req);
    }

    @PUT
    @Path("/{id}/reviewer-instances")
    public ChannelResponse setReviewerInstances(@PathParam("id") final String id,
                                                 final io.casehub.qhorus.runtime.api.core.StringListRequest req) {
        return core.setReviewerInstances(id, req);
    }

    @PUT
    @Path("/{id}/type-constraints")
    public ChannelResponse setTypeConstraints(@PathParam("id") final String id,
                                               final io.casehub.qhorus.runtime.api.core.TypeConstraintsRequest req) {
        return core.setTypeConstraints(id, req);
    }

    @PUT
    @Path("/{id}/rate-limits")
    public ChannelResponse setRateLimits(@PathParam("id") final String id,
                                          final io.casehub.qhorus.runtime.api.core.RateLimitsRequest req) {
        return core.setRateLimits(id, req);
    }

    @PUT
    @Path("/{id}/protocols")
    public ChannelResponse setProtocols(@PathParam("id") final String id,
                                         final io.casehub.qhorus.runtime.api.core.StringListRequest req) {
        return core.setProtocols(id, req);
    }

    @PUT
    @Path("/{id}/protocol-participants")
    public ChannelResponse setProtocolParticipants(@PathParam("id") final String id,
                                                    final io.casehub.qhorus.runtime.api.core.StringListRequest req) {
        return core.setProtocolParticipants(id, req);
    }

    @PUT
    @Path("/{id}/delivery-tracking")
    public ChannelResponse setDeliveryTracking(@PathParam("id") final String id,
                                                final io.casehub.qhorus.runtime.api.core.DeliveryTrackingRequest req) {
        return core.setDeliveryTracking(id, req);
    }

    @PUT
    @Path("/{id}/enforcement-mode")
    public ChannelResponse setEnforcementMode(@PathParam("id") final String id,
                                               final io.casehub.qhorus.runtime.api.core.EnforcementModeRequest req) {
        return core.setEnforcementMode(id, req);
    }

    @PUT
    @Path("/{id}/metadata")
    public ChannelResponse setMetadata(@PathParam("id") final String id,
                                        final Map<String, String> metadata) {
        return core.setMetadata(id, metadata);
    }

    @PUT
    @Path("/{id}/routing-config")
    public ChannelResponse setRoutingConfig(@PathParam("id") final String id,
                                             final io.casehub.qhorus.runtime.api.core.RoutingConfigRequest req) {
        return core.setRoutingConfig(id, req);
    }

    @org.jboss.resteasy.reactive.server.ServerExceptionMapper
    Response handleIllegalArgument(IllegalArgumentException e) {
        return Response.status(400)
                .entity(new ErrorResponse(e.getMessage()))
                .type(MediaType.APPLICATION_JSON)
                .build();
    }

    @org.jboss.resteasy.reactive.server.ServerExceptionMapper
    Response handleNotFound(NoSuchElementException e) {
        return Response.status(404)
                .entity(new ErrorResponse(e.getMessage()))
                .type(MediaType.APPLICATION_JSON)
                .build();
    }

    @org.jboss.resteasy.reactive.server.ServerExceptionMapper
    Response handleConflict(IllegalStateException e) {
        return Response.status(409)
                .entity(new ErrorResponse(e.getMessage()))
                .type(MediaType.APPLICATION_JSON)
                .build();
    }
}
