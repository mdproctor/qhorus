package io.casehub.qhorus.runtime.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.Test;

import io.casehub.platform.api.identity.TenancyConstants;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ContextNotActiveException;

/**
 * CDI-free unit tests for {@link QhorusInboundCurrentPrincipal}.
 *
 * <p>Wires beans by direct field assignment — no container needed. Tests the
 * safety-net catch that prevents {@link ContextNotActiveException} from
 * propagating when background threads call per-tenant stores.
 *
 * <p>Refs #265.
 */
class QhorusInboundCurrentPrincipalTest {

    @Test
    void tenancyId_whenContextNotActive_returnsDefaultTenantId() {
        final InboundTenancyContext thrower = new InboundTenancyContext() {
            @Override
            public String tenancyId() {
                throw new ContextNotActiveException("no request scope on this thread");
            }
        };

        final QhorusInboundCurrentPrincipal principal = new QhorusInboundCurrentPrincipal();
        principal.ctx = thrower;

        assertThat(principal.tenancyId()).isEqualTo(TenancyConstants.DEFAULT_TENANT_ID);
    }

    @Test
    void tenancyId_whenContextActive_returnsHeaderValue() {
        final InboundTenancyContext ctx = new InboundTenancyContext();
        ctx.set("tenant-abc");

        final QhorusInboundCurrentPrincipal principal = new QhorusInboundCurrentPrincipal();
        principal.ctx = ctx;

        assertThat(principal.tenancyId()).isEqualTo("tenant-abc");
    }

    @Test
    void tenancyId_whenHeaderNull_returnsDefaultTenantId() {
        final InboundTenancyContext ctx = new InboundTenancyContext();
        ctx.set(null);

        final QhorusInboundCurrentPrincipal principal = new QhorusInboundCurrentPrincipal();
        principal.ctx = ctx;

        assertThat(principal.tenancyId()).isEqualTo(TenancyConstants.DEFAULT_TENANT_ID);
    }

    @Test
    void tenancyId_whenHeaderBlank_returnsDefaultTenantId() {
        final InboundTenancyContext ctx = new InboundTenancyContext();
        ctx.set("   ");

        final QhorusInboundCurrentPrincipal principal = new QhorusInboundCurrentPrincipal();
        principal.ctx = ctx;

        assertThat(principal.tenancyId()).isEqualTo(TenancyConstants.DEFAULT_TENANT_ID);
    }

    @Test
    void actorId_returnsAnonymous() {
        final QhorusInboundCurrentPrincipal principal = withDefaultCtx();
        assertThat(principal.actorId()).isEqualTo("anonymous");
    }

    @Test
    void isCrossTenantAdmin_returnsFalse() {
        final QhorusInboundCurrentPrincipal principal = withDefaultCtx();
        assertThat(principal.isCrossTenantAdmin()).isFalse();
    }

    @Test
    void groups_returnsEmpty() {
        final QhorusInboundCurrentPrincipal principal = withDefaultCtx();
        assertThat(principal.groups()).isEqualTo(Set.of());
    }

    @Test
    void isAuthenticated_returnsFalseForAnonymousCaller() {
        final QhorusInboundCurrentPrincipal principal = withDefaultCtx();
        // anonymous actorId → isAuthenticated() returns false (documented behaviour change from mock)
        assertThat(principal.isAuthenticated()).isFalse();
    }

    @Test
    void isNotDefaultBean_soConsumerAppsHaveNoAmbiguityWithMockCurrentPrincipal() {
        // @DefaultBean on this class causes CDI ambiguity when MockCurrentPrincipal @DefaultBean
        // is also on the classpath (e.g. in DraftHouse, aml, clinical — they depend on both
        // casehub-qhorus and casehub-platform). Plain @ApplicationScoped is Tier 2 and correctly
        // yields to @Alternative @Priority(N) beans (FixedCurrentPrincipal, OidcCurrentPrincipal)
        // while displacing @DefaultBean mocks. Refs qhorus#276.
        assertThat(QhorusInboundCurrentPrincipal.class.isAnnotationPresent(DefaultBean.class))
                .as("QhorusInboundCurrentPrincipal must not be @DefaultBean — see qhorus#276")
                .isFalse();
    }

    // ── helper ────────────────────────────────────────────────────────────────

    private static QhorusInboundCurrentPrincipal withDefaultCtx() {
        final QhorusInboundCurrentPrincipal principal = new QhorusInboundCurrentPrincipal();
        principal.ctx = new InboundTenancyContext();
        return principal;
    }
}
