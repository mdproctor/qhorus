package io.casehub.qhorus.runtime.capacity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.casehub.platform.api.capacity.ActorCapacity;
import io.casehub.platform.api.capacity.CapacityPressureEvent;
import io.casehub.platform.api.capacity.RedistributionContext;
import io.casehub.platform.api.capacity.RedistributionDecision;
import io.casehub.platform.api.capacity.RedistributionPolicy;
import io.casehub.qhorus.api.message.Commitment;
import io.casehub.qhorus.api.message.CommitmentState;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.api.store.CrossTenantCommitmentStore;
import io.casehub.qhorus.runtime.ledger.MessageLedgerEntry;
import io.casehub.qhorus.runtime.ledger.MessageLedgerEntryRepository;

class QhorusRedistributionExecutorTest {

    private RedistributionDelegate delegate;
    private RedistributionPolicy policy;
    private CrossTenantCommitmentStore commitmentStore;
    private MessageLedgerEntryRepository messageRepo;
    private QhorusRedistributionExecutor executor;

    @BeforeEach
    void setup() {
        delegate = mock(RedistributionDelegate.class);
        policy = mock(RedistributionPolicy.class);
        commitmentStore = mock(CrossTenantCommitmentStore.class);
        messageRepo = mock(MessageLedgerEntryRepository.class);
        executor = new QhorusRedistributionExecutor(delegate, policy, commitmentStore, messageRepo);

        when(messageRepo.findLatestEntryByActor(anyString())).thenReturn(Optional.of(recentEntry()));
    }

    @Test
    void redistributeHappyPath() {
        var obligations = List.of(obligation("analyst"), obligation("analyst"), obligation("analyst"));
        when(commitmentStore.findOpenByObligor("agent-1")).thenReturn(obligations);
        when(commitmentStore.findLatestDelegatedByObligor("agent-1")).thenReturn(Optional.empty());
        when(policy.evaluate(any())).thenReturn(
                new RedistributionDecision.Redistribute("high pressure", Duration.ZERO, Set.of("agent-1")));
        when(delegate.redistribute(eq("agent-1"), eq(obligations), any(), anyDouble()))
                .thenReturn(new RedistributionResult(3, 3));

        executor.onCapacityPressure(event("agent-1", 0.9));

        verify(delegate).redistribute(eq("agent-1"), eq(obligations), any(), eq(0.9));
        verify(delegate, never()).escalate(anyString(), anyString());
    }

    @Test
    void compressPath() {
        var obligations = List.of(obligation("analyst"));
        when(commitmentStore.findOpenByObligor("agent-1")).thenReturn(obligations);
        when(policy.evaluate(any())).thenReturn(
                new RedistributionDecision.Compress("above compress threshold"));

        executor.onCapacityPressure(event("agent-1", 0.78));

        verify(delegate).compress("agent-1", obligations);
        verify(delegate, never()).redistribute(anyString(), anyList(), any(), anyDouble());
    }

    @Test
    void routingFailureEscalation() {
        var obligations = List.of(obligation("analyst"), obligation("analyst"));
        when(commitmentStore.findOpenByObligor("agent-1")).thenReturn(obligations);
        when(commitmentStore.findLatestDelegatedByObligor("agent-1")).thenReturn(Optional.empty());
        when(policy.evaluate(any())).thenReturn(
                new RedistributionDecision.Redistribute("high pressure", Duration.ZERO, Set.of("agent-1")));
        when(delegate.redistribute(eq("agent-1"), eq(obligations), any(), anyDouble()))
                .thenReturn(new RedistributionResult(0, 2));

        executor.onCapacityPressure(event("agent-1", 0.9));

        verify(delegate).escalate("agent-1", "redistribution requested but no targets available");
    }

    @Test
    void gracePeriodCooldownSkipsRedistribution() {
        var obligations = List.of(obligation("analyst"));
        when(commitmentStore.findOpenByObligor("agent-1")).thenReturn(obligations);
        var recentDelegation = Commitment.builder()
                .obligor("agent-1").state(CommitmentState.DELEGATED)
                .resolvedAt(Instant.now().minusSeconds(10))
                .channelId(UUID.randomUUID()).messageType(MessageType.COMMAND).build();
        when(commitmentStore.findLatestDelegatedByObligor("agent-1"))
                .thenReturn(Optional.of(recentDelegation));
        when(policy.evaluate(any())).thenReturn(
                new RedistributionDecision.Redistribute("high pressure", Duration.ofSeconds(30), Set.of("agent-1")));

        executor.onCapacityPressure(event("agent-1", 0.9));

        verify(delegate, never()).redistribute(anyString(), anyList(), any(), anyDouble());
        verify(delegate, never()).escalate(anyString(), anyString());
    }

    @Test
    void gracePeriodExpiredProceedsWithRedistribution() {
        var obligations = List.of(obligation("analyst"));
        when(commitmentStore.findOpenByObligor("agent-1")).thenReturn(obligations);
        var oldDelegation = Commitment.builder()
                .obligor("agent-1").state(CommitmentState.DELEGATED)
                .resolvedAt(Instant.now().minusSeconds(60))
                .channelId(UUID.randomUUID()).messageType(MessageType.COMMAND).build();
        when(commitmentStore.findLatestDelegatedByObligor("agent-1"))
                .thenReturn(Optional.of(oldDelegation));
        when(policy.evaluate(any())).thenReturn(
                new RedistributionDecision.Redistribute("high pressure", Duration.ofSeconds(30), Set.of("agent-1")));
        when(delegate.redistribute(eq("agent-1"), eq(obligations), any(), anyDouble()))
                .thenReturn(new RedistributionResult(1, 1));

        executor.onCapacityPressure(event("agent-1", 0.9));

        verify(delegate).redistribute(eq("agent-1"), eq(obligations), any(), anyDouble());
    }

    @Test
    void immediateThresholdBypassesGracePeriod() {
        var obligations = List.of(obligation("analyst"));
        when(commitmentStore.findOpenByObligor("agent-1")).thenReturn(obligations);
        var recentDelegation = Commitment.builder()
                .obligor("agent-1").state(CommitmentState.DELEGATED)
                .resolvedAt(Instant.now().minusSeconds(5))
                .channelId(UUID.randomUUID()).messageType(MessageType.COMMAND).build();
        when(commitmentStore.findLatestDelegatedByObligor("agent-1"))
                .thenReturn(Optional.of(recentDelegation));
        when(policy.evaluate(any())).thenReturn(
                new RedistributionDecision.Redistribute("critical", Duration.ZERO, Set.of("agent-1")));
        when(delegate.redistribute(eq("agent-1"), eq(obligations), any(), anyDouble()))
                .thenReturn(new RedistributionResult(1, 1));

        executor.onCapacityPressure(event("agent-1", 0.96));

        verify(delegate).redistribute(eq("agent-1"), eq(obligations), any(), anyDouble());
    }

    @Test
    void zeroObligationsHold() {
        when(commitmentStore.findOpenByObligor("agent-1")).thenReturn(List.of());
        when(policy.evaluate(any())).thenReturn(
                new RedistributionDecision.Hold("no movable work"));

        executor.onCapacityPressure(event("agent-1", 0.9));

        verify(delegate, never()).redistribute(anyString(), anyList(), any(), anyDouble());
        verify(delegate, never()).compress(anyString(), anyList());
        verify(delegate, never()).escalate(anyString(), anyString());
    }

    @Test
    void inactivityEscalation() {
        when(commitmentStore.findOpenByObligor("agent-1")).thenReturn(List.of());
        when(messageRepo.findLatestEntryByActor("agent-1")).thenReturn(Optional.empty());
        when(policy.evaluate(any())).thenReturn(
                new RedistributionDecision.Escalate("inactive for PT6M"));

        executor.onCapacityPressure(event("agent-1", 0.5));

        verify(delegate).escalate("agent-1", "inactive for PT6M");
    }

    @Test
    void partialRoutingSuccessNoEscalation() {
        var obligations = List.of(obligation("analyst"), obligation("analyst"), obligation("analyst"));
        when(commitmentStore.findOpenByObligor("agent-1")).thenReturn(obligations);
        when(commitmentStore.findLatestDelegatedByObligor("agent-1")).thenReturn(Optional.empty());
        when(policy.evaluate(any())).thenReturn(
                new RedistributionDecision.Redistribute("high", Duration.ZERO, Set.of("agent-1")));
        when(delegate.redistribute(eq("agent-1"), eq(obligations), any(), anyDouble()))
                .thenReturn(new RedistributionResult(2, 3));

        executor.onCapacityPressure(event("agent-1", 0.9));

        verify(delegate, never()).escalate(anyString(), anyString());
    }

    private static CapacityPressureEvent event(String actorId, double pressure) {
        return new CapacityPressureEvent(actorId,
                new ActorCapacity(actorId, pressure, Map.of("context_pressure", pressure), Instant.now()),
                0.7, "context_pressure");
    }

    private static Commitment obligation(String capabilityTag) {
        return Commitment.builder()
                .id(UUID.randomUUID()).correlationId(UUID.randomUUID().toString())
                .channelId(UUID.randomUUID()).messageType(MessageType.COMMAND)
                .requester("requester-1").obligor("agent-1")
                .state(CommitmentState.OPEN).tenancyId("tenant-1")
                .capabilityTag(capabilityTag).build();
    }

    private static MessageLedgerEntry recentEntry() {
        var entry = new MessageLedgerEntry();
        entry.occurredAt = Instant.now().minusSeconds(30);
        return entry;
    }
}
