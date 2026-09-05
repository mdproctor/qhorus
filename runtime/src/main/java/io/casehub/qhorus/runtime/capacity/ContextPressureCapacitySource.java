package io.casehub.qhorus.runtime.capacity;

import io.casehub.platform.api.capacity.CapacitySignal;
import io.casehub.platform.api.capacity.CapacitySignalSource;
import io.casehub.platform.api.capacity.CapacitySignalTypes;
import io.casehub.qhorus.runtime.ledger.MessageLedgerEntryRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@ApplicationScoped
public class ContextPressureCapacitySource implements CapacitySignalSource {

    private final MessageLedgerEntryRepository messageRepo;

    @Inject
    public ContextPressureCapacitySource(MessageLedgerEntryRepository messageRepo) {
        this.messageRepo = messageRepo;
    }

    @Override
    public String signalType() {
        return CapacitySignalTypes.CONTEXT_PRESSURE;
    }

    @Override
    public Optional<CapacitySignal> observe(String actorId) {
        return messageRepo.findLatestContextPressureForActor(actorId)
                .map(entry -> new CapacitySignal(
                        actorId,
                        CapacitySignalTypes.CONTEXT_PRESSURE,
                        entry.contextWindowPct / 100.0,
                        entry.occurredAt,
                        Map.of("channelId", entry.subjectId.toString())));
    }

    @Override
    public List<CapacitySignal> observeOverloaded(double threshold) {
        return messageRepo.findLatestContextPressureGlobal().stream()
                .filter(entry -> entry.contextWindowPct != null
                                 && entry.contextWindowPct / 100.0 >= threshold)
                .map(entry -> new CapacitySignal(
                        entry.actorId,
                        CapacitySignalTypes.CONTEXT_PRESSURE,
                        entry.contextWindowPct / 100.0,
                        entry.occurredAt,
                        Map.of()))
                .toList();
    }
}
