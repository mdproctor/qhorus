package io.casehub.qhorus.runtime.api;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import io.casehub.qhorus.api.gateway.ChannelRef;
import io.casehub.qhorus.runtime.channel.Channel;
import io.casehub.qhorus.runtime.channel.ChannelService;
import io.casehub.qhorus.runtime.config.QhorusConfig;
import io.casehub.qhorus.runtime.message.Commitment;
import io.casehub.qhorus.runtime.message.CommitmentService;
import io.casehub.qhorus.runtime.message.Message;
import io.casehub.qhorus.runtime.message.MessageService;
import io.casehub.qhorus.api.message.MessageType;
import io.quarkus.arc.properties.UnlessBuildProperty;

/**
 * Optional A2A-compatible REST endpoint layer.
 *
 * <p>
 * When {@code casehub.qhorus.a2a.enabled=true}, exposes two endpoints that let external
 * A2A orchestrators delegate tasks to Qhorus without knowing it is an MCP server:
 * <ul>
 * <li>{@code POST /a2a/message:send} — thin adapter delegating to {@link A2AChannelBackend}</li>
 * <li>{@code GET /a2a/tasks/{id}} — returns A2A Task status via CommitmentStore lookup,
 *     falling back to message-history deriveState; always includes history</li>
 * </ul>
 *
 * <p>
 * Both endpoints return HTTP 501 Not Implemented when the flag is off, protecting
 * existing deployments from unintended exposure.
 *
 * @see <a href="https://google.github.io/A2A/">Google A2A Protocol</a>
 */
@UnlessBuildProperty(name = "casehub.qhorus.reactive.enabled", stringValue = "true", enableIfMissing = true)
@Path("/a2a")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
public class A2AResource {

    private static final Response A2A_DISABLED = Response
            .status(Response.Status.NOT_IMPLEMENTED)
            .entity("{\"error\":\"A2A endpoint is disabled. Set casehub.qhorus.a2a.enabled=true to activate.\"}")
            .type(MediaType.APPLICATION_JSON)
            .build();

    @Inject
    QhorusConfig config;

    @Inject
    A2AChannelBackend a2aBackend;

    @Inject
    CommitmentService commitmentService;

    @Inject
    MessageService messageService;

    @Inject
    ChannelService channelService;

    @POST
    @Path("/message:send")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response sendMessage(SendMessageRequest request, @Context HttpHeaders headers) {
        if (!config.a2a().enabled()) {
            return A2A_DISABLED;
        }

        // Validate inbound request
        if (request == null || request.message() == null) {
            return error400("message is required");
        }
        A2AMessage msg = request.message();

        if (msg.contextId() == null || msg.contextId().isBlank()) {
            return error400("message.contextId (channel name) is required");
        }
        if (msg.parts() == null || msg.parts().isEmpty()) {
            return error400("message.parts must contain at least one text part");
        }
        String text = msg.parts().stream()
                .filter(p -> "text".equals(p.kind()) && p.text() != null)
                .map(A2APart::text)
                .findFirst()
                .orElse(null);
        if (text == null) {
            return error400("message.parts must contain at least one text part with kind=text");
        }

        // Look up channel
        Channel channel = channelService.findByName(msg.contextId()).orElse(null);
        if (channel == null) {
            return error400("channel not found: " + msg.contextId());
        }

        // Register A2A backend on this channel (idempotent)
        a2aBackend.ensureRegistered(channel.id, new ChannelRef(channel.id, channel.name));

        // Extract actor-type override header
        String actorTypeHeader = headers.getHeaderString("x-qhorus-actor-type");

        // Delegate to backend — gets full pipeline (type resolution, ledger, commitment)
        Map<String, String> metadata = msg.metadata() != null ? msg.metadata() : Map.of();
        String taskId = (msg.taskId() != null && !msg.taskId().isBlank())
                ? msg.taskId()
                : UUID.randomUUID().toString();

        try {
            a2aBackend.receive(msg.contextId(), msg.role(), text, taskId, metadata, actorTypeHeader);
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            return error400(cause.getMessage());
        }

        Task task = new Task(taskId, msg.contextId(), new TaskStatus("submitted"), null);
        return Response.ok(new SendMessageResponse(task)).build();
    }

    @GET
    @Path("/tasks/{id}")
    @Transactional
    public Response getTask(@PathParam("id") String taskId) {
        if (!config.a2a().enabled()) {
            return A2A_DISABLED;
        }

        // Get all messages (needed for history AND as fallback for state)
        List<Message> messages = messageService.findAllByCorrelationId(taskId);
        if (messages.isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("{\"error\":\"Task not found: " + taskId + "\"}")
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        }

        Channel channel = channelService.findById(messages.get(0).channelId)
                .orElseThrow(() -> new IllegalStateException("Channel not found for task " + taskId));

        // Determine state: CommitmentStore first (durable), fallback to deriveState
        Commitment commitment = commitmentService.findByCorrelationId(taskId).orElse(null);
        String state = (commitment != null) ? toA2AState(commitment.state) : deriveState(messages);

        // Build history — ALWAYS include (existing tests depend on this)
        List<A2AMessage> history = messages.stream()
                .map(m -> new A2AMessage(
                        m.sender,
                        m.content != null ? List.of(new A2APart("text", m.content)) : List.of(),
                        null,
                        m.correlationId,
                        channel.name,
                        null))
                .toList();

        return Response.ok(new Task(taskId, channel.name, new TaskStatus(state), history)).build();
    }

    private static String toA2AState(io.casehub.qhorus.api.message.CommitmentState state) {
        return switch (state) {
            case FULFILLED, DELEGATED -> "completed";
            case FAILED, DECLINED, EXPIRED -> "failed";
            case ACKNOWLEDGED -> "working";
            case OPEN -> "submitted";
        };
    }

    private static String deriveState(List<Message> messages) {
        MessageType lastType = null;
        for (Message m : messages) {
            lastType = m.messageType;
        }
        if (lastType == null)
            return "submitted";
        return switch (lastType) {
            case RESPONSE, DONE -> "completed";
            case FAILURE, DECLINE -> "failed";
            case STATUS -> "working";
            default -> "submitted";
        };
    }

    private static Response error400(String message) {
        return Response.status(Response.Status.BAD_REQUEST)
                .entity("{\"error\":\"" + message + "\"}")
                .type(MediaType.APPLICATION_JSON)
                .build();
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
            String contextId,
            java.util.Map<String, String> metadata) {
    }

    /** A2A content part — only text kind is supported. */
    public record A2APart(String kind, String text) {
    }

    /** A2A Task returned by send and get endpoints. */
    public record Task(String id, String contextId, TaskStatus status,
            java.util.List<A2AMessage> history) {
    }

    /** A2A TaskStatus — state is one of: submitted, working, completed, failed. */
    public record TaskStatus(String state) {
    }

    /** Top-level response wrapper for the send endpoint. */
    public record SendMessageResponse(Task task) {
    }
}
