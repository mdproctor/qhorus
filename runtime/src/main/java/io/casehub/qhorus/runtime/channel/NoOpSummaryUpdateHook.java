package io.casehub.qhorus.runtime.channel;

import io.casehub.qhorus.api.spi.SummaryUpdateContext;
import io.casehub.qhorus.api.spi.SummaryUpdateHook;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;

@DefaultBean
@ApplicationScoped
public class NoOpSummaryUpdateHook implements SummaryUpdateHook {
    @Override
    public String update(SummaryUpdateContext context) {
        return context.currentSummary();
    }
}
