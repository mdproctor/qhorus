package io.casehub.qhorus.runtime.message;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.casehub.ledger.runtime.service.TrustGateService;
import io.casehub.qhorus.api.spi.ObligorTrustContext;
import io.casehub.qhorus.api.spi.ObligorTrustPolicy;
import io.casehub.qhorus.runtime.config.QhorusConfig;
import io.quarkus.arc.DefaultBean;

/**
 * Default {@link ObligorTrustPolicy} that delegates to {@link TrustGateService} using
 * the configured {@code casehub.qhorus.commitment.min-obligor-trust} threshold.
 *
 * <p>Returns {@code true} immediately when the threshold is zero or negative (gate disabled).
 *
 * <p>Refs #213.
 */
@DefaultBean
@ApplicationScoped
public class DefaultObligorTrustPolicy implements ObligorTrustPolicy {

    @Inject
    QhorusConfig config;

    @Inject
    TrustGateService trustGateService;

    @Override
    public boolean permits(ObligorTrustContext ctx) {
        double minTrust = config.commitment().minObligorTrust();
        if (minTrust <= 0.0) {
            return true;
        }
        return trustGateService.meetsThreshold(ctx.obligorId(), minTrust);
    }
}
