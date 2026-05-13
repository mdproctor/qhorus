package io.casehub.qhorus.runtime.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.casehub.ledger.api.model.ActorType;
import io.casehub.qhorus.runtime.instance.Instance;
import io.casehub.qhorus.runtime.instance.InstanceService;

/**
 * Pure unit test — no Quarkus context needed.
 */
class A2AActorResolverTest {

    private InstanceService instanceService;
    private A2AActorResolver resolver;

    @BeforeEach
    void setup() {
        instanceService = mock(InstanceService.class);
        resolver = new A2AActorResolver();
        resolver.instanceService = instanceService;
    }

    // ── role:"agent" unconditional ─────────────────────────────────────────────

    @Test
    void roleAgent_noHeader_noMetadata_isAgent() {
        assertEquals(ActorType.AGENT, resolver.resolve("agent", null, Map.of()));
    }

    @Test
    void roleAgent_withHumanHeader_stillAgent() {
        // role:"agent" is unconditional — header cannot override it to HUMAN
        assertEquals(ActorType.AGENT, resolver.resolve("agent", "HUMAN", Map.of()));
    }

    // ── Step 1: explicit header ────────────────────────────────────────────────

    @Test
    void roleUser_headerAgent_isAgent() {
        assertEquals(ActorType.AGENT, resolver.resolve("user", "AGENT", Map.of()));
    }

    @Test
    void roleUser_headerHuman_isHuman() {
        assertEquals(ActorType.HUMAN, resolver.resolve("user", "HUMAN", Map.of()));
    }

    @Test
    void roleUser_headerSystem_isSystem() {
        assertEquals(ActorType.SYSTEM, resolver.resolve("user", "SYSTEM", Map.of()));
    }

    @Test
    void roleUser_invalidHeader_fallsThrough_isHuman() {
        // Invalid header value — silently falls through to chain, no exception
        assertEquals(ActorType.HUMAN, resolver.resolve("user", "BANANA", Map.of()));
    }

    // ── Step 2: Instance registry ──────────────────────────────────────────────

    @Test
    void roleUser_agentIdInRegistry_isAgent() {
        when(instanceService.findByInstanceId("registry-agent"))
                .thenReturn(Optional.of(new Instance()));
        assertEquals(ActorType.AGENT, resolver.resolve("user", null,
                Map.of("agentId", "registry-agent")));
    }

    // ── Step 3: agentCardUrl ───────────────────────────────────────────────────

    @Test
    void roleUser_agentCardUrlPresent_isAgent() {
        assertEquals(ActorType.AGENT, resolver.resolve("user", null,
                Map.of("agentCardUrl", "https://example.com/.well-known/agent-card.json")));
    }

    @Test
    void roleUser_agentCardUrlBlank_fallsThrough_isHuman() {
        assertEquals(ActorType.HUMAN, resolver.resolve("user", null,
                Map.of("agentCardUrl", "")));
    }

    // ── Steps 4+5: ActorTypeResolver on agentId ────────────────────────────────

    @Test
    void roleUser_personaAgentId_isAgent() {
        when(instanceService.findByInstanceId("claude:orchestrator@v1"))
                .thenReturn(Optional.empty());
        assertEquals(ActorType.AGENT, resolver.resolve("user", null,
                Map.of("agentId", "claude:orchestrator@v1")));
    }

    @Test
    void roleUser_systemAgentId_isSystem() {
        when(instanceService.findByInstanceId("system:scheduler"))
                .thenReturn(Optional.empty());
        assertEquals(ActorType.SYSTEM, resolver.resolve("user", null,
                Map.of("agentId", "system:scheduler")));
    }

    // ── Step 6: default ────────────────────────────────────────────────────────

    @Test
    void roleUser_noSignals_isHuman() {
        assertEquals(ActorType.HUMAN, resolver.resolve("user", null, Map.of()));
    }

    @Test
    void nullMetadata_isHuman_noNPE() {
        // null metadata passed as empty map — must not throw
        assertEquals(ActorType.HUMAN, resolver.resolve("user", null, Map.of()));
    }
}
