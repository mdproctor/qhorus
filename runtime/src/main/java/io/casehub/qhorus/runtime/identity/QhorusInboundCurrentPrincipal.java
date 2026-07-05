package io.casehub.qhorus.runtime.identity;

import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.ContextNotActiveException;
import jakarta.inject.Inject;

import io.casehub.platform.api.identity.CurrentPrincipal;
import io.casehub.platform.api.identity.TenancyConstants;

/**
 * Qhorus default {@link CurrentPrincipal} for HTTP requests without OIDC authentication.
 *
 * <p>Reads {@code tenancyId} from {@link InboundTenancyContext}, which is populated by
 * {@link TenancyContextFilter} from the {@code X-Tenancy-ID} request header.
 *
 * <p><strong>CDI resolution:</strong> plain {@code @ApplicationScoped} (Tier 2 per
 * persistence-backend-cdi-priority protocol). Displaces {@code MockCurrentPrincipal @DefaultBean}
 * automatically. Displaced by any {@code @Alternative @Priority(N)} bean: {@code FixedCurrentPrincipal
 * @Priority(1)} in test fixtures, or {@code OidcCurrentPrincipal @Priority(100)} when
 * {@code casehub-platform-oidc} is on the classpath. Must NOT be {@code @DefaultBean} —
 * two {@code @DefaultBean} implementations of the same type in consumer deployments (e.g.
 * DraftHouse, aml, clinical) cause CDI ambiguity. Refs qhorus#276.
 *
 * <p><strong>Scope:</strong> {@code @ApplicationScoped} (not {@code @RequestScoped}).
 * The bean itself is stateless — per-request isolation comes from the
 * {@code @RequestScoped InboundTenancyContext} it delegates to. Using
 * {@code @ApplicationScoped} here ensures that when background threads (Scheduled,
 * ObservesAsync) call per-tenant stores, the CDI proxy resolves and the
 * {@link ContextNotActiveException} from {@link InboundTenancyContext#tenancyId()}
 * is caught at the call site, returning {@link TenancyConstants#DEFAULT_TENANT_ID}
 * safely. With {@code @RequestScoped}, the proxy itself would throw before entering
 * the method body and the catch would be unreachable.
 *
 * <p><strong>Security boundary:</strong> {@code X-Tenancy-ID} is not authenticated.
 * Any caller can claim any tenant. For isolated multi-tenant deployments,
 * use {@code casehub-platform-oidc}.
 *
 * <p><strong>Behavioural delta from MockCurrentPrincipal:</strong>
 * {@code actorId()} returns {@code "anonymous"}, making {@code isAuthenticated()}
 * return {@code false}. The mock defaults to {@code "system"} (configurable).
 * No qhorus-internal code currently gates on {@code isAuthenticated()}.
 *
 * <p>Refs qhorus#265, qhorus#276.
 */
@ApplicationScoped
public class QhorusInboundCurrentPrincipal implements CurrentPrincipal {

    @Inject
    InboundTenancyContext ctx;

    @Override
    public String actorId() {
        return "anonymous";
    }

    @Override
    public Set<String> groups() {
        return Set.of();
    }

    @Override
    public String tenancyId() {
        try {
            return ctx.tenancyId();
        } catch (final ContextNotActiveException e) {
            // Background threads have no request scope — InboundTenancyContext is unreachable.
            // Per PP-20260609-scheduled-service-cross-tenant-stores, background code should use
            // CrossTenant*Store interfaces. This catch is a safety net; DEFAULT_TENANT_ID is correct here.
            return TenancyConstants.DEFAULT_TENANT_ID;
        }
    }

    @Override
    public boolean isCrossTenantAdmin() {
        return false;
    }
}
