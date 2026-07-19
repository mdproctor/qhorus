package io.casehub.qhorus.api.spi;

import io.smallrye.mutiny.Uni;

@FunctionalInterface
public interface ReactiveSummaryUpdateHook {
    Uni<String> update(SummaryUpdateContext context);
}
