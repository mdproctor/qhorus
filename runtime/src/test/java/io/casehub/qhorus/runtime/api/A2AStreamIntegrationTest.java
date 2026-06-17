package io.casehub.qhorus.runtime.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import jakarta.inject.Inject;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.sse.SseEventSource;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.casehub.qhorus.api.message.DispatchResult;
import io.casehub.qhorus.api.message.MessageDispatch;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.api.A2AEnabledProfile;
import io.casehub.qhorus.runtime.channel.ChannelCreateRequest;
import io.casehub.qhorus.runtime.channel.ChannelService;
import io.casehub.qhorus.runtime.message.MessageService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

/**
 * Integration tests for GET /a2a/tasks/{id}/stream — covers all SSE paths.
 * Replaces A2AStreamTaskTest (immediate-close paths migrated from RestAssured to SseEventSource)
 * and adds live-stream and keepalive tests.
 *
 * <p>Package io.casehub.qhorus.runtime.api gives access to the package-private
 * A2AChannelBackend.streamCount() used to synchronize stream registration.
 *
 * <p>Refs qhorus#277, qhorus#278.
 */
@QuarkusTest
@TestProfile(A2AEnabledProfile.class)
class A2AStreamIntegrationTest {

    @TestHTTPResource("") URI baseUri;

    @Inject A2AChannelBackend a2aBackend;
    @Inject ChannelService channelService;
    @Inject MessageService messageService;

    // ── Immediate-close paths (migrated from A2AStreamTaskTest) ──────────────

    @Test
    void sseStream_taskNotFound_returnsErrorEvent() throws Exception {
        final String taskId = UUID.randomUUID().toString();
        final CopyOnWriteArrayList<String> events = new CopyOnWriteArrayList<>();
        final CountDownLatch latch = new CountDownLatch(1);

        final Client client = ClientBuilder.newClient();
        try {
            final WebTarget target = client.target(baseUri)
                    .path("/a2a/tasks/" + taskId + "/stream");
            try (SseEventSource source = SseEventSource.target(target)
                    .reconnectingEvery(Long.MAX_VALUE, TimeUnit.MILLISECONDS)
                    .build()) {
                source.register(event -> {
                    events.add(event.getName() + "|" + event.readData(String.class));
                    latch.countDown();
                });
                source.open();
                assertThat(latch.await(5, TimeUnit.SECONDS)).as("No SSE event received within 5s").isTrue();
            }
        } finally {
            client.close();
        }

        assertThat(events).anyMatch(e -> e.startsWith("error|") && e.contains("\"final\":true"));
    }

    @Test
    void sseStream_invalidUuid_returnsErrorEvent() throws Exception {
        final CopyOnWriteArrayList<String> events = new CopyOnWriteArrayList<>();
        final CountDownLatch latch = new CountDownLatch(1);

        final Client client = ClientBuilder.newClient();
        try {
            final WebTarget target = client.target(baseUri)
                    .path("/a2a/tasks/not-a-uuid/stream");
            try (SseEventSource source = SseEventSource.target(target)
                    .reconnectingEvery(Long.MAX_VALUE, TimeUnit.MILLISECONDS)
                    .build()) {
                source.register(event -> {
                    events.add(event.getName() + "|" + event.readData(String.class));
                    latch.countDown();
                });
                source.open();
                assertThat(latch.await(5, TimeUnit.SECONDS)).as("No SSE event received within 5s").isTrue();
            }
        } finally {
            client.close();
        }

        assertThat(events).anyMatch(e -> e.startsWith("error|") && e.contains("\"final\":true"));
    }

    @Test
    void sseStream_alreadyTerminalDone_sendsImmediateFinalEvent() throws Exception {
        final String channelName = "stream-done-" + UUID.randomUUID();
        final String taskId = UUID.randomUUID().toString();
        final UUID[] chId = {null};
        final Long[] cmdId = {null};

        QuarkusTransaction.requiringNew().run(() -> channelService.create(new ChannelCreateRequest(
                channelName, "SSE test", ChannelSemantic.APPEND,
                null, null, null, null, null, null, null, null, null, null, null)));
        QuarkusTransaction.requiringNew().run(() ->
                chId[0] = channelService.findByName(channelName).orElseThrow().id);
        QuarkusTransaction.requiringNew().run(() -> {
            final DispatchResult r = messageService.dispatch(MessageDispatch.builder()
                    .channelId(chId[0]).sender("requester").type(MessageType.COMMAND)
                    .content("do this").correlationId(taskId).actorType(ActorType.AGENT).build());
            cmdId[0] = r.messageId();
        });
        QuarkusTransaction.requiringNew().run(() ->
                messageService.dispatch(MessageDispatch.builder()
                        .channelId(chId[0]).sender("agent").type(MessageType.DONE)
                        .content("done").correlationId(taskId).inReplyTo(cmdId[0])
                        .actorType(ActorType.AGENT).build()));

        final CopyOnWriteArrayList<String> events = new CopyOnWriteArrayList<>();
        final CountDownLatch latch = new CountDownLatch(1);
        final Client client = ClientBuilder.newClient();
        try {
            final WebTarget target = client.target(baseUri)
                    .path("/a2a/tasks/" + taskId + "/stream");
            try (SseEventSource source = SseEventSource.target(target)
                    .reconnectingEvery(Long.MAX_VALUE, TimeUnit.MILLISECONDS)
                    .build()) {
                source.register(event -> { events.add(event.getName() + "|" + event.readData(String.class)); latch.countDown(); });
                source.open();
                assertThat(latch.await(5, TimeUnit.SECONDS)).as("No SSE event received within 5s").isTrue();
            }
        } finally {
            client.close();
        }

        assertThat(events).anyMatch(e ->
                e.startsWith("task_status_update|") && e.contains("\"state\":\"completed\"") && e.contains("\"final\":true"));
    }

    @Test
    void sseStream_alreadyTerminalDecline_sendsCancelledEvent() throws Exception {
        final String channelName = "stream-decline-" + UUID.randomUUID();
        final String taskId = UUID.randomUUID().toString();
        final UUID[] chId = {null};
        final Long[] cmdId = {null};

        QuarkusTransaction.requiringNew().run(() -> channelService.create(new ChannelCreateRequest(
                channelName, "SSE decline test", ChannelSemantic.APPEND,
                null, null, null, null, null, null, null, null, null, null, null)));
        QuarkusTransaction.requiringNew().run(() ->
                chId[0] = channelService.findByName(channelName).orElseThrow().id);
        QuarkusTransaction.requiringNew().run(() -> {
            final DispatchResult r = messageService.dispatch(MessageDispatch.builder()
                    .channelId(chId[0]).sender("requester").type(MessageType.COMMAND)
                    .content("do this").correlationId(taskId).actorType(ActorType.AGENT).build());
            cmdId[0] = r.messageId();
        });
        QuarkusTransaction.requiringNew().run(() ->
                messageService.dispatch(MessageDispatch.builder()
                        .channelId(chId[0]).sender("agent").type(MessageType.DECLINE)
                        .content("I refuse").correlationId(taskId).inReplyTo(cmdId[0])
                        .actorType(ActorType.AGENT).build()));

        final CopyOnWriteArrayList<String> events = new CopyOnWriteArrayList<>();
        final CountDownLatch latch = new CountDownLatch(1);
        final Client client = ClientBuilder.newClient();
        try {
            final WebTarget target = client.target(baseUri)
                    .path("/a2a/tasks/" + taskId + "/stream");
            try (SseEventSource source = SseEventSource.target(target)
                    .reconnectingEvery(Long.MAX_VALUE, TimeUnit.MILLISECONDS)
                    .build()) {
                source.register(event -> { events.add(event.getName() + "|" + event.readData(String.class)); latch.countDown(); });
                source.open();
                assertThat(latch.await(5, TimeUnit.SECONDS)).as("No SSE event received within 5s").isTrue();
            }
        } finally {
            client.close();
        }

        assertThat(events).anyMatch(e ->
                e.startsWith("task_status_update|") && e.contains("\"state\":\"cancelled\"") && e.contains("\"final\":true"));
    }

    // ── Live-stream paths (new) ───────────────────────────────────────────────

    @Test
    void sseStream_receivesCompletedEvent_whenDoneDispatched() throws Exception {
        final String channelName = "stream-live-done-" + UUID.randomUUID();
        final UUID corrId = UUID.randomUUID();
        final String taskId = corrId.toString();

        final ChannelSetup setup = createChannelAndDispatchCommand(channelName, taskId);

        final CopyOnWriteArrayList<String> events = new CopyOnWriteArrayList<>();
        final CountDownLatch latch = new CountDownLatch(1);
        final Client client = ClientBuilder.newClient();
        try {
            final WebTarget target = client.target(baseUri)
                    .path("/a2a/tasks/" + taskId + "/stream");
            try (SseEventSource source = SseEventSource.target(target)
                    .reconnectingEvery(Long.MAX_VALUE, TimeUnit.MILLISECONDS)
                    .build()) {
                source.register(event -> { events.add(event.getName() + "|" + event.readData(String.class)); latch.countDown(); });
                source.open();

                Awaitility.await().atMost(2, TimeUnit.SECONDS)
                        .until(() -> a2aBackend.streamCount(corrId) > 0);

                QuarkusTransaction.requiringNew().run(() ->
                        messageService.dispatch(MessageDispatch.builder()
                                .channelId(setup.channelId()).sender("agent").type(MessageType.DONE)
                                .content("done").correlationId(taskId).inReplyTo(setup.commandMessageId())
                                .actorType(ActorType.AGENT).build()));

                assertThat(latch.await(10, TimeUnit.SECONDS)).as("No SSE event received within 10s").isTrue();
            }
        } finally {
            client.close();
        }

        assertThat(events).anyMatch(e ->
                e.startsWith("task_status_update|") && e.contains("\"state\":\"completed\"") && e.contains("\"final\":true"));
    }

    @Test
    void sseStream_receivesCancelledEvent_whenDeclineDispatched() throws Exception {
        final String channelName = "stream-live-decline-" + UUID.randomUUID();
        final UUID corrId = UUID.randomUUID();
        final String taskId = corrId.toString();

        final ChannelSetup setup = createChannelAndDispatchCommand(channelName, taskId);

        final CopyOnWriteArrayList<String> events = new CopyOnWriteArrayList<>();
        final CountDownLatch latch = new CountDownLatch(1);
        final Client client = ClientBuilder.newClient();
        try {
            final WebTarget target = client.target(baseUri)
                    .path("/a2a/tasks/" + taskId + "/stream");
            try (SseEventSource source = SseEventSource.target(target)
                    .reconnectingEvery(Long.MAX_VALUE, TimeUnit.MILLISECONDS)
                    .build()) {
                source.register(event -> { events.add(event.getName() + "|" + event.readData(String.class)); latch.countDown(); });
                source.open();

                Awaitility.await().atMost(2, TimeUnit.SECONDS)
                        .until(() -> a2aBackend.streamCount(corrId) > 0);

                QuarkusTransaction.requiringNew().run(() ->
                        messageService.dispatch(MessageDispatch.builder()
                                .channelId(setup.channelId()).sender("agent").type(MessageType.DECLINE)
                                .content("I refuse").correlationId(taskId).inReplyTo(setup.commandMessageId())
                                .actorType(ActorType.AGENT).build()));

                assertThat(latch.await(10, TimeUnit.SECONDS)).as("No SSE event received within 10s").isTrue();
            }
        } finally {
            client.close();
        }

        assertThat(events).anyMatch(e ->
                e.startsWith("task_status_update|") && e.contains("\"state\":\"cancelled\"") && e.contains("\"final\":true"));
    }

    @Test
    void sseStream_keepaliveEventsDoNotInterfereWithTaskStream() throws Exception {
        // Named keepalive events (event: keepalive) are sent on each poll timeout to keep
        // bytes flowing over the wire and prevent proxy idle-timeout TCP teardown.
        // They fire the SseEventSource event handler but are filtered out here — only
        // non-keepalive events count as task events.
        // heartbeat-interval-seconds=1 in A2AEnabledProfile means ≥3 keepalives in 3s.
        final String channelName = "stream-keepalive-" + UUID.randomUUID();
        final UUID corrId = UUID.randomUUID();
        final String taskId = corrId.toString();

        // Dispatch COMMAND so task exists — without it, streamTask() returns immediately
        // with "task not found" and fires the event handler, self-defeating the test.
        createChannelAndDispatchCommand(channelName, taskId);

        final CopyOnWriteArrayList<String> events = new CopyOnWriteArrayList<>();
        final Client client = ClientBuilder.newClient();
        try {
            final WebTarget target = client.target(baseUri)
                    .path("/a2a/tasks/" + taskId + "/stream");
            try (SseEventSource source = SseEventSource.target(target)
                    .reconnectingEvery(Long.MAX_VALUE, TimeUnit.MILLISECONDS)
                    .build()) {
                source.register(event -> {
                    if (!"keepalive".equals(event.getName())) {
                        events.add(event.getName() + "|" + event.readData(String.class));
                    }
                });
                source.open();

                Awaitility.await().atMost(2, TimeUnit.SECONDS)
                        .until(() -> a2aBackend.streamCount(corrId) > 0);

                // Load-bearing sleep: keepalive events are filtered out, so there is no signal to await.
                // 3s > 3 × heartbeat-interval-seconds=1, confirming ≥3 keepalive cycles pass
                // without producing any task_status_update events.
                Thread.sleep(3_000);

                assertThat(events).as("Keepalive events must not appear as task events").isEmpty();
                assertThat(a2aBackend.streamCount(corrId))
                        .as("Connection must still be open after keepalives").isGreaterThan(0);
                // SseEventSource.close() in try-with-resources triggers sink.isClosed() → loop exits
            }
        } finally {
            client.close();
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private record ChannelSetup(UUID channelId, long commandMessageId) {}

    private ChannelSetup createChannelAndDispatchCommand(final String channelName, final String taskId) {
        QuarkusTransaction.requiringNew().run(() -> channelService.create(new ChannelCreateRequest(
                channelName, "SSE test", ChannelSemantic.APPEND,
                null, null, null, null, null, null, null, null, null, null, null)));
        final UUID[] chId = {null};
        QuarkusTransaction.requiringNew().run(() ->
                chId[0] = channelService.findByName(channelName).orElseThrow().id);
        final Long[] cmdMsgId = {null};
        QuarkusTransaction.requiringNew().run(() -> {
            final DispatchResult r = messageService.dispatch(MessageDispatch.builder()
                    .channelId(chId[0]).sender("requester").type(MessageType.COMMAND)
                    .content("do this").correlationId(taskId).actorType(ActorType.AGENT).build());
            cmdMsgId[0] = r.messageId();
        });
        return new ChannelSetup(chId[0], cmdMsgId[0]);
    }
}
