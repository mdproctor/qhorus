package io.casehub.qhorus.runtime.message;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.UUID;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.casehub.ledger.api.model.ActorTrustScore.ScoreType;
import io.casehub.ledger.runtime.repository.ActorTrustScoreRepository;
import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.casehub.qhorus.api.message.MessageDispatch;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.channel.Channel;
import io.casehub.qhorus.runtime.store.ChannelStore;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

/**
 * Integration tests for the trust gate in MessageService.dispatch().
 * Uses TrustGateProfile (minObligorTrust=0.5).
 * ActorTrustScore is stored in the qhorus PU (TrustGateProfile sets casehub.ledger.datasource=qhorus,
 * which routes @LedgerPersistenceUnit EntityManager there; named queries are registered in that PU).
 * Refs #199
 */
@QuarkusTest
@TestProfile(TrustGateProfile.class)
class TrustGateTest {

    @Inject
    MessageService messageService;

    @Inject
    ChannelStore channelStore;

    @Inject
    ActorTrustScoreRepository trustScoreRepository;

    private Channel appendChannel(String name) {
        Channel ch = new Channel();
        ch.name = name;
        ch.semantic = ChannelSemantic.APPEND;
        return channelStore.put(ch);
    }

    private void seedTrustScore(String actorId, double score) {
        trustScoreRepository.upsert(
                actorId, ScoreType.GLOBAL,
                null, null,
                ActorType.AGENT, score,
                0, 0, 0.0, 0.0,
                0, 0,
                Instant.now());
    }

    @Test
    @TestTransaction
    void dispatch_command_rejectsObligorBelowThreshold() {
        Channel ch = appendChannel("trust-gate-reject-" + UUID.randomUUID());
        String target = "low-trust-agent-" + UUID.randomUUID();

        seedTrustScore(target, 0.3);  // below 0.5 threshold

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                messageService.dispatch(MessageDispatch.builder()
                        .channelId(ch.id)
                        .sender("sender-agent")
                        .type(MessageType.COMMAND)
                        .content("do something")
                        .correlationId(UUID.randomUUID().toString())
                        .target(target)
                        .actorType(ActorType.AGENT)
                        .build()));
        assertTrue(ex.getMessage().contains("did not meet the trust threshold"),
                "Exception should mention trust score threshold");
    }

    @Test
    @TestTransaction
    void dispatch_command_allowsObligorAboveThreshold() {
        Channel ch = appendChannel("trust-gate-allow-" + UUID.randomUUID());
        String target = "high-trust-agent-" + UUID.randomUUID();

        seedTrustScore(target, 0.8);  // above 0.5 threshold

        assertDoesNotThrow(() ->
                messageService.dispatch(MessageDispatch.builder()
                        .channelId(ch.id)
                        .sender("sender-agent")
                        .type(MessageType.COMMAND)
                        .content("do something")
                        .correlationId(UUID.randomUUID().toString())
                        .target(target)
                        .actorType(ActorType.AGENT)
                        .build()));
    }

    @Test
    @TestTransaction
    void dispatch_command_withNullTarget_bypassesTrustGate() {
        Channel ch = appendChannel("trust-gate-null-target-" + UUID.randomUUID());

        // No trust score seeded; null target → gate skipped entirely
        assertDoesNotThrow(() ->
                messageService.dispatch(MessageDispatch.builder()
                        .channelId(ch.id)
                        .sender("sender-agent")
                        .type(MessageType.COMMAND)
                        .content("broadcast command")
                        .correlationId(UUID.randomUUID().toString())
                        .actorType(ActorType.AGENT)
                        .build()));
    }

    @Test
    @TestTransaction
    void dispatch_command_withRoleTarget_bypassesTrustGate() {
        Channel ch = appendChannel("trust-gate-role-" + UUID.randomUUID());

        // Role-prefixed target → gate skipped (cannot resolve to specific actor)
        assertDoesNotThrow(() ->
                messageService.dispatch(MessageDispatch.builder()
                        .channelId(ch.id)
                        .sender("sender-agent")
                        .type(MessageType.COMMAND)
                        .content("role command")
                        .correlationId(UUID.randomUUID().toString())
                        .target("role:specialist")
                        .actorType(ActorType.AGENT)
                        .build()));
    }

    @Test
    @TestTransaction
    void dispatch_command_noScoreForObligor_isRejectedWhenGateEnabled() {
        Channel ch = appendChannel("trust-gate-noscore-" + UUID.randomUUID());
        String target = "new-agent-no-score-" + UUID.randomUUID();

        // No ActorTrustScore row — DefaultObligorTrustPolicy delegates to TrustGateService,
        // which returns false for unknown actors when the gate is enabled
        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                messageService.dispatch(MessageDispatch.builder()
                        .channelId(ch.id)
                        .sender("sender-agent")
                        .type(MessageType.COMMAND)
                        .content("command to new agent")
                        .correlationId(UUID.randomUUID().toString())
                        .target(target)
                        .actorType(ActorType.AGENT)
                        .build()));
        assertTrue(ex.getMessage().contains("did not meet the trust threshold"),
                "New agent with no trust history is rejected when gate is enabled");
    }

    @Test
    @TestTransaction
    void dispatch_nonCommand_notSubjectToTrustGate() {
        Channel ch = appendChannel("trust-gate-query-" + UUID.randomUUID());
        String sender = "agent-" + UUID.randomUUID();

        // QUERY type — gate does not apply regardless of target
        assertDoesNotThrow(() ->
                messageService.dispatch(MessageDispatch.builder()
                        .channelId(ch.id)
                        .sender(sender)
                        .type(MessageType.QUERY)
                        .content("question")
                        .correlationId(UUID.randomUUID().toString())
                        .target("untrusted-agent-" + UUID.randomUUID())
                        .actorType(ActorType.AGENT)
                        .build()));
    }
}
