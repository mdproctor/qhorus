package io.casehub.qhorus.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.casehub.ledger.runtime.service.TrustGateService;
import io.casehub.qhorus.api.spi.ObligorTrustPolicy;
import io.casehub.qhorus.runtime.channel.ChannelService;
import io.casehub.qhorus.runtime.data.DataService;
import io.casehub.qhorus.runtime.instance.InstanceService;
import io.casehub.qhorus.runtime.ledger.LedgerWriteService;
import io.casehub.qhorus.runtime.message.CommitmentService;
import io.casehub.qhorus.runtime.message.MessageService;
import io.casehub.qhorus.runtime.message.ReactiveMessageService;
import io.casehub.qhorus.runtime.watchdog.WatchdogEvaluationService;
import io.smallrye.mutiny.Uni;

/**
 * Structural verification that blocking-tier service beans contain no
 * {@code Uni<T>}-returning methods.
 *
 * <p>
 * Enforces PP-20260519-f2e160 (reactive-blocking-tier-separation) going forward.
 * Pure reflection — no Quarkus context, no CDI. Fast and cheap to run on every build.
 */
class BlockingTierPurityTest {

    @Test
    void channelService_hasNoUniMethods() {
        assertNoUniMethods(ChannelService.class);
    }

    @Test
    void messageService_hasNoUniMethods() {
        assertNoUniMethods(MessageService.class);
    }

    @Test
    void instanceService_hasNoUniMethods() {
        assertNoUniMethods(InstanceService.class);
    }

    @Test
    void dataService_hasNoUniMethods() {
        assertNoUniMethods(DataService.class);
    }

    @Test
    void commitmentService_hasNoUniMethods() {
        assertNoUniMethods(CommitmentService.class);
    }

    @Test
    void ledgerWriteService_hasNoUniMethods() {
        assertNoUniMethods(LedgerWriteService.class);
    }

    @Test
    void watchdogEvaluationService_hasNoUniMethods() {
        assertNoUniMethods(WatchdogEvaluationService.class);
    }

    @Test
    void reactiveMessageService_trustGate_usesObligorTrustPolicySpi_notTrustGateServiceDirectly() {
        // ReactiveMessageService must delegate trust gate decisions to ObligorTrustPolicy SPI
        // (which honours custom bean overrides), not call TrustGateService directly.
        // TrustGateService is still called internally by DefaultObligorTrustPolicy — but that is
        // an implementation detail of the default bean, not a structural dependency of the
        // reactive dispatch service. Refs qhorus#235.
        final List<String> fieldNames = Arrays.stream(ReactiveMessageService.class.getDeclaredFields())
                .map(Field::getName)
                .toList();
        assertTrue(fieldNames.contains("obligorTrustPolicy"),
                "ReactiveMessageService must inject ObligorTrustPolicy to honour custom SPI beans. Refs #235");
        assertFalse(fieldNames.stream().anyMatch(n -> n.equals("trustGateService")),
                "ReactiveMessageService must not inject TrustGateService directly in the trust gate — " +
                "delegate to ObligorTrustPolicy instead. Refs #235");
    }

    private static void assertNoUniMethods(final Class<?> cls) {
        final List<String> uniMethods = Arrays.stream(cls.getDeclaredMethods())
                .filter(m -> Uni.class.isAssignableFrom(m.getReturnType()))
                .map(Method::getName)
                .toList();
        assertTrue(uniMethods.isEmpty(),
                String.format("%s must contain no Uni<T>-returning methods — reactive variants belong in Reactive%s. Found: %s",
                        cls.getSimpleName(), cls.getSimpleName(), uniMethods));
    }
}
