package io.casehub.qhorus.runtime.watchdog;

import io.casehub.qhorus.api.store.ReactiveWatchdogStore;
import io.casehub.qhorus.api.store.query.WatchdogQuery;
import io.casehub.qhorus.api.watchdog.Watchdog;
import io.quarkus.arc.properties.IfBuildProperty;
import io.quarkus.hibernate.reactive.panache.Panache;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@IfBuildProperty(name = "casehub.qhorus.reactive.enabled", stringValue = "true")
@ApplicationScoped
public class ReactiveWatchdogService {

    @Inject
    ReactiveWatchdogStore watchdogStore;

    public Uni<Watchdog> register(String conditionType, String targetName, Integer thresholdSeconds,
                                  Integer thresholdCount, Integer similarityPct,
                                  String notificationChannel, String createdBy, String tenancyId) {
        return Panache.withTransaction("qhorus", () -> {
            io.casehub.qhorus.api.watchdog.WatchdogConditionType type = io.casehub.qhorus.api.watchdog.WatchdogConditionType.fromString(conditionType)
                                                                                                                            .orElseThrow(() -> new IllegalArgumentException("Unknown condition_type: " + conditionType));
            Watchdog w = Watchdog.builder(type, targetName)
                                 .thresholdSeconds(thresholdSeconds)
                                 .thresholdCount(thresholdCount)
                                 .similarityPct(similarityPct)
                                 .notificationChannel(notificationChannel)
                                 .createdBy(createdBy)
                                 .tenancyId(tenancyId)
                                 .build();
            return watchdogStore.put(w);
        });
    }

    public Uni<List<Watchdog>> listAll() {
        return watchdogStore.scan(WatchdogQuery.all());
    }

    public Uni<List<Watchdog>> listByTenant(String tenancyId) {
        return watchdogStore.scan(WatchdogQuery.byTenancy(tenancyId));
    }

    public Uni<Optional<Watchdog>> findById(UUID id) {
        return watchdogStore.find(id);
    }

    public Uni<Boolean> delete(UUID id) {
        return Panache.withTransaction("qhorus", () -> watchdogStore.find(id).flatMap(opt -> {
            if (opt.isEmpty()) {
                return Uni.createFrom().item(false);
            }
            return watchdogStore.delete(id).map(ignored -> true);
        }));
    }
}
