package io.casehub.qhorus.persistence.memory.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.casehub.qhorus.runtime.gateway.DeliveryCursor;
import io.casehub.qhorus.runtime.store.DeliveryCursorStore;

abstract class DeliveryCursorStoreContractTest {

    protected abstract DeliveryCursorStore store();

    @BeforeEach
    void clearStore() {
        // Subclasses may override for cleanup
    }

    @Test
    void save_newCursor_assignsId() {
        DeliveryCursor cursor = cursor(UUID.randomUUID(), "backend-1", 100L);
        DeliveryCursor saved = store().save(cursor);
        assertThat(saved.id).isNotNull();
    }

    @Test
    void findByChannelAndBackend_exists_returnsIt() {
        UUID channelId = UUID.randomUUID();
        store().save(cursor(channelId, "slack", 50L));
        assertThat(store().findByChannelAndBackend(channelId, "slack"))
                .isPresent()
                .hasValueSatisfying(c -> {
                    assertThat(c.channelId).isEqualTo(channelId);
                    assertThat(c.backendId).isEqualTo("slack");
                    assertThat(c.lastDeliveredId).isEqualTo(50L);
                });
    }

    @Test
    void findByChannelAndBackend_absent_returnsEmpty() {
        assertThat(store().findByChannelAndBackend(UUID.randomUUID(), "nonexistent"))
                .isEmpty();
    }

    @Test
    void findByChannel_returnsAllForChannel() {
        UUID ch = UUID.randomUUID();
        store().save(cursor(ch, "slack", 10L));
        store().save(cursor(ch, "connector", 20L));
        store().save(cursor(UUID.randomUUID(), "other", 30L));
        assertThat(store().findByChannel(ch)).hasSize(2);
    }

    @Test
    void findAll_returnsEverything() {
        store().save(cursor(UUID.randomUUID(), "a", 1L));
        store().save(cursor(UUID.randomUUID(), "b", 2L));
        assertThat(store().findAll()).hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    void deleteByChannel_removesOnlyThatChannel() {
        UUID ch1 = UUID.randomUUID();
        UUID ch2 = UUID.randomUUID();
        store().save(cursor(ch1, "slack", 10L));
        store().save(cursor(ch2, "slack", 20L));
        store().deleteByChannel(ch1);
        assertThat(store().findByChannel(ch1)).isEmpty();
        assertThat(store().findByChannel(ch2)).hasSize(1);
    }

    @Test
    void save_existingCursor_updatesLastDeliveredId() {
        UUID ch = UUID.randomUUID();
        DeliveryCursor saved = store().save(cursor(ch, "slack", 10L));
        saved.lastDeliveredId = 50L;
        saved.updatedAt = Instant.now();
        store().save(saved);
        assertThat(store().findByChannelAndBackend(ch, "slack"))
                .hasValueSatisfying(c -> assertThat(c.lastDeliveredId).isEqualTo(50L));
    }

    static DeliveryCursor cursor(UUID channelId, String backendId, Long lastDeliveredId) {
        DeliveryCursor c = new DeliveryCursor();
        c.channelId = channelId;
        c.backendId = backendId;
        c.lastDeliveredId = lastDeliveredId;
        c.createdAt = Instant.now();
        c.updatedAt = Instant.now();
        return c;
    }
}
