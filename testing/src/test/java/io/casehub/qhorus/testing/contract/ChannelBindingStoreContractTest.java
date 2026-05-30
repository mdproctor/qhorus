package io.casehub.qhorus.testing.contract;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.casehub.qhorus.runtime.channel.ChannelConnectorBinding;
import io.casehub.qhorus.runtime.store.ChannelBindingStore;

public abstract class ChannelBindingStoreContractTest {

    protected abstract ChannelBindingStore store();

    protected abstract void reset();

    @BeforeEach
    void beforeEach() {
        reset();
    }

    @Test
    void put_andFindByChannelId_returnsBinding() {
        UUID channelId = UUID.randomUUID();
        ChannelConnectorBinding b = binding(channelId, "sms", "ext-001", "twilio", "+15550001111");
        store().put(b);
        Optional<ChannelConnectorBinding> found = store().findByChannelId(channelId);
        assertTrue(found.isPresent());
        assertEquals(channelId, found.get().channelId);
        assertEquals("+15550001111", found.get().outboundDestination);
    }

    @Test
    void put_andFindByKey_returnsBinding() {
        UUID channelId = UUID.randomUUID();
        ChannelConnectorBinding b = binding(channelId, "sms", "ext-002", "twilio", "+15550002222");
        store().put(b);
        Optional<ChannelConnectorBinding> found = store().findByKey("sms", "ext-002");
        assertTrue(found.isPresent());
        assertEquals(channelId, found.get().channelId);
    }

    @Test
    void findByChannelId_absentChannel_returnsEmpty() {
        assertTrue(store().findByChannelId(UUID.randomUUID()).isEmpty());
    }

    @Test
    void findByKey_absentKey_returnsEmpty() {
        assertTrue(store().findByKey("sms", "nosuch-key").isEmpty());
    }

    @Test
    void delete_removesBinding() {
        UUID channelId = UUID.randomUUID();
        ChannelConnectorBinding b = binding(channelId, "sms", "ext-del", "twilio", "+15550003333");
        store().put(b);
        store().delete(channelId);
        assertTrue(store().findByChannelId(channelId).isEmpty());
        assertTrue(store().findByKey("sms", "ext-del").isEmpty());
    }

    @Test
    void put_overwritesExistingBinding() {
        UUID channelId = UUID.randomUUID();
        ChannelConnectorBinding first = binding(channelId, "sms", "ext-upd", "twilio", "+1111");
        store().put(first);
        ChannelConnectorBinding second = binding(channelId, "sms", "ext-upd2", "twilio", "+2222");
        store().put(second);
        Optional<ChannelConnectorBinding> found = store().findByChannelId(channelId);
        assertTrue(found.isPresent());
        assertEquals("+2222", found.get().outboundDestination);
        assertTrue(store().findByKey("sms", "ext-upd").isEmpty());
        assertTrue(store().findByKey("sms", "ext-upd2").isPresent());
    }

    @Test
    void findAll_emptyStore_returnsEmptyMap() {
        assertTrue(store().findAll().isEmpty());
    }

    @Test
    void findAll_afterPut_containsBinding() {
        UUID channelId = UUID.randomUUID();
        store().put(binding(channelId, "sms", "key-fa1", "twilio", "+1"));
        Map<UUID, ChannelConnectorBinding> result = store().findAll();
        assertTrue(result.containsKey(channelId));
        assertEquals("+1", result.get(channelId).outboundDestination);
    }

    @Test
    void findAll_afterDelete_excludesDeletedBinding() {
        UUID channelId = UUID.randomUUID();
        store().put(binding(channelId, "sms", "key-fa2", "twilio", "+2"));
        store().delete(channelId);
        assertFalse(store().findAll().containsKey(channelId));
    }

    @Test
    void findAll_returnsSnapshotNotLiveView() {
        UUID channelId = UUID.randomUUID();
        store().put(binding(channelId, "sms", "key-fa3", "twilio", "+3"));
        Map<UUID, ChannelConnectorBinding> snapshot = store().findAll();
        store().delete(channelId);
        assertTrue(snapshot.containsKey(channelId)); // snapshot unchanged after delete
    }

    protected ChannelConnectorBinding binding(UUID channelId, String connectorId,
            String externalKey, String outConnectorId, String dest) {
        ChannelConnectorBinding b = new ChannelConnectorBinding();
        b.channelId = channelId;
        b.inboundConnectorId = connectorId;
        b.externalKey = externalKey;
        b.outboundConnectorId = outConnectorId;
        b.outboundDestination = dest;
        return b;
    }
}
