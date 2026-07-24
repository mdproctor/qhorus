package io.casehub.qhorus.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import java.util.UUID;

import jakarta.inject.Inject;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.casehub.qhorus.runtime.mcp.QhorusMcpTools;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

/**
 * Integration tests verifying that {@code X-Tenancy-ID} header routing
 * works end-to-end through {@code TenancyContextFilter → InboundTenancyContext →
 * QhorusInboundCurrentPrincipal} to the A2A endpoints.
 *
 * <p>Channel creation happens in test code (no HTTP request scope) — the
 * {@code ContextNotActiveException} catch in {@link
 * io.casehub.qhorus.runtime.identity.QhorusInboundCurrentPrincipal} fires and
 * returns {@code DEFAULT_TENANT_ID}, so channels land in the default tenant.
 *
 * <p>Refs #265.
 */
@QuarkusTest
@TestProfile(A2AEnabledProfile.class)
class A2ATenantScopingTest {

    // Unique per test to avoid UQ_CHANNEL_NAME_TENANCY constraint on re-run (DB_CLOSE_DELAY=-1).
    private String channel;

    @Inject
    QhorusMcpTools tools;

    // ── channel setup ─────────────────────────────────────────────────────────

    @BeforeEach
    void ensureChannel() {
        // Random name: DB_CLOSE_DELAY=-1 keeps data between tests; unique names avoid UQ_CHANNEL_NAME_TENANCY.
        // Runs outside HTTP scope → QhorusInboundCurrentPrincipal catches ContextNotActiveException
        // → DEFAULT_TENANT_ID is used. Channel is created in DEFAULT_TENANT_ID.
        channel = "a2a-ts-" + UUID.randomUUID().toString().substring(0, 8);
        final String ch = channel;
        QuarkusTransaction.requiringNew().run(() ->
                tools.createChannel(ch, "A2A tenant scoping test channel", "APPEND", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null));
    }

    // ── test: no header → DEFAULT_TENANT_ID → channel found → 200 ─────────────

    @Test
    void sendMessage_withoutTenancyHeader_usesDefaultTenantAndSucceeds() {
        final String taskId = UUID.randomUUID().toString();

        given()
                .urlEncodingEnabled(false)
                .contentType("application/json")
                .body(body(channel, taskId))
                .when().post("/a2a/message:send")
                .then().statusCode(200)
                .body("task.status.state", equalTo("submitted"));
    }

    // ── test: wrong-tenant header → channel not found (proves filter sets tenant) ─

    @Test
    void sendMessage_withNonExistentTenantHeader_returns400ChannelNotFound() {
        // Filter sets tenancyId = "non-existent-tenant" — channel is in DEFAULT_TENANT_ID
        // → channelService.findByName() returns empty → 400 "channel not found"
        given()
                .urlEncodingEnabled(false)
                .contentType("application/json")
                .header("X-Tenancy-ID", "non-existent-tenant")
                .body(body(channel, UUID.randomUUID().toString()))
                .when().post("/a2a/message:send")
                .then().statusCode(400);
    }

    // ── test: getTask() with same tenant header → task found ─────────────────

    @Test
    void getTask_withMatchingTenantContext_returnsTask() {
        final String taskId = UUID.randomUUID().toString();

        // Send without header → DEFAULT_TENANT_ID
        given()
                .urlEncodingEnabled(false)
                .contentType("application/json")
                .body(body(channel, taskId))
                .when().post("/a2a/message:send")
                .then().statusCode(200);

        // GET without header → DEFAULT_TENANT_ID → task found
        given()
                .when().get("/a2a/tasks/" + taskId)
                .then().statusCode(200)
                .body("id", equalTo(taskId));
    }

    // ── test: getTask() with wrong-tenant header → 404 (tenant asymmetry) ─────

    @Test
    void getTask_withDifferentTenantHeader_returns404() {
        final String taskId = UUID.randomUUID().toString();

        // Send without header → DEFAULT_TENANT_ID
        given()
                .urlEncodingEnabled(false)
                .contentType("application/json")
                .body(body(channel, taskId))
                .when().post("/a2a/message:send")
                .then().statusCode(200);

        // GET with different-tenant → queries "different-tenant" bucket → task not found
        given()
                .header("X-Tenancy-ID", "different-tenant")
                .when().get("/a2a/tasks/" + taskId)
                .then().statusCode(404);
    }

    // ── test: commitment-level isolation — both tasks in same channel, getTask()
    // can only see its own tenant's commitment; Refs #267 ─────────────────────

    @Test
    void getTask_commitmentLevelTenantIsolation_crossTenantTaskNotVisible() {
        // Two tasks created in DEFAULT_TENANT_ID (no header → ContextNotActiveException → default).
        final String taskA = UUID.randomUUID().toString();
        final String taskB = UUID.randomUUID().toString();

        // Submit task A (DEFAULT_TENANT_ID)
        given().urlEncodingEnabled(false).contentType("application/json")
                .body(body(channel, taskA))
                .when().post("/a2a/message:send")
                .then().statusCode(200);

        // Submit task B (DEFAULT_TENANT_ID)
        given().urlEncodingEnabled(false).contentType("application/json")
                .body(body(channel, taskB))
                .when().post("/a2a/message:send")
                .then().statusCode(200);

        // Both tasks visible to DEFAULT_TENANT_ID (no header)
        given().when().get("/a2a/tasks/" + taskA).then().statusCode(200);
        given().when().get("/a2a/tasks/" + taskB).then().statusCode(200);

        // Neither task visible to a different tenant — commitment query is tenant-scoped
        given().header("X-Tenancy-ID", "tenant-other-267")
                .when().get("/a2a/tasks/" + taskA).then().statusCode(404);
        given().header("X-Tenancy-ID", "tenant-other-267")
                .when().get("/a2a/tasks/" + taskB).then().statusCode(404);
    }

    // ── helper ─────────────────────────────────────────────────────────────────

    private static String body(final String channel, final String taskId) {
        return """
                {"message":{"role":"user","parts":[{"kind":"text","text":"Hello from test"}],\
                "contextId":"%s","taskId":"%s"}}""".formatted(channel, taskId);
    }
}
