package io.casehub.qhorus.runtime.channel;

import io.casehub.qhorus.api.spi.ReactiveSummaryUpdateHook;
import io.casehub.qhorus.api.spi.SummaryUpdateContext;
import io.casehub.qhorus.api.spi.SummaryUpdateHook;
import io.quarkus.arc.DefaultBean;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@DefaultBean
@ApplicationScoped
public class DefaultReactiveSummaryUpdateHook implements ReactiveSummaryUpdateHook {

    @Inject
    SummaryUpdateHook blockingHook;

    @Override
    public Uni<String> update(SummaryUpdateContext context) {
        return Uni.createFrom().item(() -> blockingHook.update(context))
                .runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }
}
