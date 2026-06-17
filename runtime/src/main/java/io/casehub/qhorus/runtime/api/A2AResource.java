package io.casehub.qhorus.runtime.api;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

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
import jakarta.ws.rs.sse.Sse;
import jakarta.ws.rs.sse.SseEventSink;

import org.jboss.logging.Logger;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.smallrye.common.annotation.RunOnVirtualThread;

import io.casehub.qhorus.api.gateway.OutboundMessage;

import io.casehub.qhorus.api.gateway.ChannelRef;
import io.casehub.qhorus.api.message.CommitmentState;
import io.casehub.qhorus.runtime.channel.Channel;
import io.casehub.qhorus.runtime.channel.ChannelService;
import io.casehub.qhorus.runtime.config.QhorusConfig;
import io.casehub.qhorus.runtime.message.Commitment;
import io.casehub.qhorus.runtime.message.CommitmentService;
import io.casehub.qhorus.runtime.message.Message;
import io.casehub.qhorus.runtime.message.MessageService;
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
 *     falling back to message-history via A2ATaskState.fromMessageHistory; always includes history</li>
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

    private static final Logger LOG = Logger.getLogger(A2AResource.class);

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

    /**
     * Returns the A2A task status for the given task ID.
     *
     * <p><strong>Tenant asymmetry hazard:</strong> messages are stored with the tenant in effect
     * when they were sent (from {@code X-Tenancy-ID} header or default tenant). This endpoint
     * filters results by the tenant in effect at query time. Calling without {@code X-Tenancy-ID}
     * when the task was sent with one, or with a different header value, returns HTTP 404 even
     * though the task exists — it just exists in a different tenant bucket.
     *
     * <p>Always include {@code X-Tenancy-ID} consistently on both
     * {@code POST /a2a/message:send} and {@code GET /a2a/tasks/{id}} for the same task.
     */
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
                    .entity(new ErrorResponse("Task not found: " + taskId))
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        }

        Channel channel = channelService.findById(messages.get(0).channelId)
                .orElseThrow(() -> new IllegalStateException("Channel not found for task " + taskId));

        // Determine state: CommitmentStore for non-OPEN states (terminal/acknowledged give
        // definitive results); fall back to message history for OPEN commitments and the
        // no-commitment case (message history is more informative, e.g. HANDOFF → "working").
        Commitment commitment = commitmentService.findByCorrelationId(taskId).orElse(null);
        String state = (commitment != null && commitment.state != CommitmentState.OPEN)
                ? A2ATaskState.fromCommitmentState(commitment.state)
                : A2ATaskState.fromMessageHistory(messages);

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

    /**
     * SSE stream endpoint — active virtual-thread model.
     *
     * <p>The virtual thread stays alive for the connection duration and owns all
     * lifecycle concerns: keepalive (via {@code event: keepalive} named SSE event on poll timeout),
     * orphan detection
     * (sink.isClosed() at top of every iteration), and max-duration enforcement (deadline).
     *
     * <p>A {@link LinkedBlockingQueue} is the synchronization primitive between this thread
     * and {@link A2AChannelBackend#post} — the consumer is simply {@code queue::offer}.
     * All SSE writes happen on this thread (no concurrent write issues).
     *
     * <p>Transaction scope: two short-lived {@code QuarkusTransaction.requiringNew()} calls
     * (validation + re-check) commit immediately. The loop runs outside any transaction.
     *
     * <p>Refs qhorus#278, qhorus#277.
     */
    @GET
    @Path("/tasks/{id}/stream")
    @Produces("text/event-stream")
    @RunOnVirtualThread
    public void streamTask(
            @PathParam("id") final String taskId,
            @Context final SseEventSink sink,
            @Context final Sse sse) throws Exception {

        // ── Steps 1–2: immediate exits (outside try-finally, no consumer registered) ──

        if (!config.a2a().enabled()) {
            sendErrorEvent(sink, sse, taskId, "A2A endpoint is disabled");
            return;
        }

        final UUID corrId;
        try {
            corrId = UUID.fromString(taskId);
        } catch (final IllegalArgumentException e) {
            sendErrorEvent(sink, sse, taskId, "Invalid task ID format — expected UUID");
            return;
        }

        // Short-lived transactional reads — commits before loop starts
        final AtomicBoolean notFound = new AtomicBoolean(false);
        final AtomicReference<String> stateRef = new AtomicReference<>("submitted");
        final AtomicReference<UUID> channelIdRef = new AtomicReference<>();
        final AtomicReference<String> channelNameRef = new AtomicReference<>();
        QuarkusTransaction.requiringNew().run(() -> {
            final List<Message> messages = messageService.findAllByCorrelationId(taskId);
            if (messages.isEmpty()) {
                notFound.set(true);
                return;
            }
            final Commitment commitment = commitmentService.findByCorrelationId(taskId).orElse(null);
            final String state = (commitment != null && commitment.state != CommitmentState.OPEN)
                    ? A2ATaskState.fromCommitmentState(commitment.state)
                    : A2ATaskState.fromMessageHistory(messages);
            stateRef.set(state);
            // Capture channel for ensureRegistered below
            final Message first = messages.get(0);
            channelIdRef.set(first.channelId);
            final Channel ch = channelService.findById(first.channelId).orElse(null);
            if (ch != null) channelNameRef.set(ch.name);
        });

        if (notFound.get()) {
            sendErrorEvent(sink, sse, taskId, "Task not found: " + taskId);
            return;
        }
        if (A2ATaskState.TERMINAL_STATES.contains(stateRef.get())) {
            sendStatusEvent(sink, sse, taskId, stateRef.get());
            return;
        }

        // Ensure A2AChannelBackend is registered on this channel so fanOut() reaches it.
        // sendMessage() does this via POST /a2a/message:send; direct-dispatch tests bypass
        // that path, so we self-register here (idempotent). Refs qhorus#278.
        final UUID channelId = channelIdRef.get();
        final String channelName = channelNameRef.get();
        if (channelId != null && channelName != null) {
            a2aBackend.ensureRegistered(channelId, new ChannelRef(channelId, channelName));
        } else if (channelId != null) {
            LOG.warnf("streamTask: channel %s not found for ensureRegistered — " +
                    "fanOut() will not reach this SSE consumer", channelId);
        }

        // ── Step 3: register consumer ──────────────────────────────────────────
        final LinkedBlockingQueue<OutboundMessage> queue = new LinkedBlockingQueue<>();
        final Consumer<OutboundMessage> consumer = queue::offer;
        a2aBackend.registerStream(corrId, consumer);

        // ── Outer try: covers steps 4 + 5 — finally always deregisters ─────────
        try {
            // Step 4: re-check after registration — closes dispatch-during-registration race.
            // Messages dispatched after registerStream() go into the queue; this re-check
            // catches terminal messages that committed before the initial read but were
            // not yet DB-visible at that point.
            final AtomicReference<String> recheckRef = new AtomicReference<>("submitted");
            QuarkusTransaction.requiringNew().run(() -> {
                final List<Message> messages = messageService.findAllByCorrelationId(taskId);
                final Commitment commitment = commitmentService.findByCorrelationId(taskId).orElse(null);
                final String state = (commitment != null && commitment.state != CommitmentState.OPEN)
                        ? A2ATaskState.fromCommitmentState(commitment.state)
                        : A2ATaskState.fromMessageHistory(messages);
                recheckRef.set(state);
            });
            if (A2ATaskState.TERMINAL_STATES.contains(recheckRef.get())) {
                sendStatusEvent(sink, sse, taskId, recheckRef.get());
                return; // finally deregisters
            }

            // Step 5: keepalive loop
            final long heartbeatMs = config.a2a().sse().heartbeatIntervalSeconds() * 1000L;
            final long deadline = System.currentTimeMillis()
                    + (long) config.a2a().sse().maxDurationSeconds() * 1000L;

            try {
                while (true) {
                    if (sink.isClosed()) break; // orphan: client disconnected
                    final long remaining = deadline - System.currentTimeMillis();
                    if (remaining <= 0) break; // max duration exceeded

                    final OutboundMessage msg;
                    try {
                        msg = queue.poll(Math.min(heartbeatMs, remaining), TimeUnit.MILLISECONDS);
                    } catch (final InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }

                    if (msg == null) {
                        // Poll timeout — send named keepalive event to prevent proxy
                        // idle-timeout TCP teardown. A named event sends bytes over the wire;
                        // clients that ignore "keepalive" events are unaffected.
                        // SSE comments are not used here: RESTEasy SseEventSource fires
                        // event handlers for comment-only frames (non-compliant with SSE spec),
                        // which would corrupt integration test assertions.
                        sink.send(sse.newEventBuilder().name("keepalive").data("").build());
                        continue;
                    }

                    final boolean terminal = A2ATaskState.TERMINAL_TYPES.contains(msg.type());
                    final String state = A2ATaskState.fromMessageType(msg.type());
                    final String json = "{\"id\":\"%s\",\"status\":{\"state\":\"%s\"},\"final\":%b}"
                            .formatted(taskId, state, terminal);
                    final CompletionStage<Void> send = sink.send(
                            sse.newEventBuilder().name("task_status_update").data(json).build());
                    if (terminal) {
                        send.toCompletableFuture().get(5, TimeUnit.SECONDS); // await before close
                        break;
                    }
                }
            } catch (final Exception e) {
                LOG.warnf(e, "SSE stream error for task %s", taskId);
            }
        } finally {
            a2aBackend.deregisterStream(corrId, consumer);
            if (!sink.isClosed()) sink.close();
        }
    }

    /**
     * Sends a terminal status event and closes the sink.
     *
     * <p>Awaits {@link SseEventSink#send} synchronously (safe on a virtual thread — parks
     * without blocking an OS thread) to ensure the payload reaches the client before close.
     * The internal try-finally guarantees the sink is closed even if get() throws.
     */
    private static void sendStatusEvent(final SseEventSink sink, final Sse sse,
            final String taskId, final String state) throws Exception {
        final String json = "{\"id\":\"%s\",\"status\":{\"state\":\"%s\"},\"final\":true}"
                .formatted(taskId, state);
        try {
            sink.send(sse.newEventBuilder().name("task_status_update").data(json).build())
                    .toCompletableFuture().get(5, TimeUnit.SECONDS);
        } finally {
            if (!sink.isClosed()) sink.close();
        }
    }

    /**
     * Sends an error event and closes the sink.
     *
     * <p>SSE void methods cannot return a different HTTP status — this endpoint always
     * returns HTTP 200 with text/event-stream content type. The {@code event:error} type
     * lets clients distinguish error events from status updates.
     *
     * <p>Awaits send and closes in try-finally — same guarantee as {@link #sendStatusEvent}.
     */
    private static void sendErrorEvent(final SseEventSink sink, final Sse sse,
            final String taskId, final String error) throws Exception {
        final String json = "{\"id\":\"%s\",\"error\":\"%s\",\"final\":true}"
                .formatted(taskId, error);
        try {
            sink.send(sse.newEventBuilder().name("error").data(json).build())
                    .toCompletableFuture().get(5, TimeUnit.SECONDS);
        } finally {
            if (!sink.isClosed()) sink.close();
        }
    }

    private static Response error400(final String message) {
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(new ErrorResponse(message))
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
