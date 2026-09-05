package io.casehub.qhorus.runtime.capacity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.casehub.platform.api.capacity.CapacitySignalTypes;
import io.casehub.qhorus.runtime.ledger.MessageLedgerEntry;
import io.casehub.qhorus.runtime.ledger.MessageLedgerEntryRepository;

class ContextPressureCapacitySourceTest {

    @Test
    void observeReturnsPressureFromLatestContextWindowPct() {
        var repo = mock(MessageLedgerEntryRepository.class);
        var entry = createEntry("agent-1", 85, UUID.randomUUID());
        when(repo.findLatestContextPressureForActor("agent-1"))
                .thenReturn(Optional.of(entry));

        var source = new ContextPressureCapacitySource(repo);

        var signal = source.observe("agent-1");
        assertThat(signal).isPresent();
        assertThat(signal.get().pressure()).isCloseTo(0.85, within(0.001));
        assertThat(signal.get().signalType()).isEqualTo(CapacitySignalTypes.CONTEXT_PRESSURE);
        assertThat(signal.get().actorId()).isEqualTo("agent-1");
    }

    @Test
    void observeReturnsEmptyForUnknownActor() {
        var repo = mock(MessageLedgerEntryRepository.class);
        when(repo.findLatestContextPressureForActor("unknown")).thenReturn(Optional.empty());

        var source = new ContextPressureCapacitySource(repo);
        assertThat(source.observe("unknown")).isEmpty();
    }

    @Test
    void observeOverloadedRespectsThresholdBoundary() {
        var repo = mock(MessageLedgerEntryRepository.class);
        var entry70 = createEntry("agent-at-70", 70, UUID.randomUUID());
        var entry85 = createEntry("agent-at-85", 85, UUID.randomUUID());

        when(repo.findLatestContextPressureGlobal()).thenReturn(List.of(entry70, entry85));

        var source = new ContextPressureCapacitySource(repo);
        var overloaded = source.observeOverloaded(0.80);

        assertThat(overloaded).hasSize(1);
        assertThat(overloaded.get(0).actorId()).isEqualTo("agent-at-85");
        assertThat(overloaded.get(0).pressure()).isCloseTo(0.85, within(0.001));
    }

    @Test
    void observeOverloadedIncludesExactThreshold() {
        var repo = mock(MessageLedgerEntryRepository.class);
        var entry = createEntry("agent-at-70", 70, UUID.randomUUID());
        when(repo.findLatestContextPressureGlobal()).thenReturn(List.of(entry));

        var source = new ContextPressureCapacitySource(repo);
        var overloaded = source.observeOverloaded(0.70);

        assertThat(overloaded).hasSize(1);
    }

    @Test
    void signalTypeIsContextPressure() {
        var source = new ContextPressureCapacitySource(mock(MessageLedgerEntryRepository.class));
        assertThat(source.signalType()).isEqualTo(CapacitySignalTypes.CONTEXT_PRESSURE);
    }

    private static MessageLedgerEntry createEntry(String actorId, int pct, UUID channelId) {
        var entry = new MessageLedgerEntry();
        entry.actorId = actorId;
        entry.contextWindowPct = pct;
        entry.subjectId = channelId;
        entry.occurredAt = Instant.now();
        return entry;
    }
}
