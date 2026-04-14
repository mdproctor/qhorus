package io.quarkiverse.qhorus.runtime.api;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import io.quarkiverse.qhorus.runtime.config.QhorusConfig;

/**
 * Optional A2A-compatible REST endpoint layer.
 *
 * <p>
 * When {@code quarkus.qhorus.a2a.enabled=true}, exposes two endpoints that let external
 * A2A orchestrators delegate tasks to Qhorus without knowing it is an MCP server:
 * <ul>
 * <li>{@code POST /a2a/message:send} — maps an A2A SendMessageRequest to {@code send_message}</li>
 * <li>{@code GET /a2a/tasks/{id}} — returns A2A Task status via correlation_id lookup</li>
 * </ul>
 *
 * <p>
 * Both endpoints return HTTP 501 Not Implemented when the flag is off, protecting
 * existing deployments from unintended exposure.
 *
 * @see <a href="https://google.github.io/A2A/">Google A2A Protocol</a>
 */
@Path("/a2a")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
public class A2AResource {

    private static final Response A2A_DISABLED = Response
            .status(Response.Status.NOT_IMPLEMENTED)
            .entity("{\"error\":\"A2A endpoint is disabled. Set quarkus.qhorus.a2a.enabled=true to activate.\"}")
            .type(MediaType.APPLICATION_JSON)
            .build();

    @Inject
    QhorusConfig config;

    @POST
    @Path("/message:send")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response sendMessage(SendMessageRequest request) {
        if (!config.a2a().enabled()) {
            return A2A_DISABLED;
        }
        // Implemented in #34
        return Response.status(Response.Status.NOT_IMPLEMENTED).build();
    }

    @GET
    @Path("/tasks/{id}")
    public Response getTask(@PathParam("id") String taskId) {
        if (!config.a2a().enabled()) {
            return A2A_DISABLED;
        }
        // Implemented in #35
        return Response.status(Response.Status.NOT_IMPLEMENTED).build();
    }

    // -----------------------------------------------------------------------
    // A2A request / response model records
    // -----------------------------------------------------------------------

    /** Inbound A2A SendMessageRequest body. */
    public record SendMessageRequest(String id, A2AMessage message) {
    }

    /** A2A Message as defined by the A2A protocol. */
    public record A2AMessage(
            String role,
            java.util.List<A2APart> parts,
            String messageId,
            String taskId,
            String contextId) {
    }

    /** A2A content part — only text kind is supported. */
    public record A2APart(String kind, String text) {
    }

    /** A2A Task returned by send and get endpoints. */
    public record Task(String id, String contextId, TaskStatus status,
            java.util.List<A2AMessage> history) {
    }

    /** A2A TaskStatus — state is one of: submitted, working, completed. */
    public record TaskStatus(String state) {
    }

    /** Top-level response wrapper for the send endpoint. */
    public record SendMessageResponse(Task task) {
    }
}
