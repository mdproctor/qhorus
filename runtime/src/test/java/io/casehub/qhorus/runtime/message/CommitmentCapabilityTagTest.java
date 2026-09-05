package io.casehub.qhorus.runtime.message;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.casehub.qhorus.api.message.Commitment;
import io.casehub.qhorus.api.message.CommitmentState;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.api.store.CommitmentStore;
import io.casehub.qhorus.runtime.config.QhorusTracingConfig;

class CommitmentCapabilityTagTest {

    private SimpleCommitmentStore store;
    private CommitmentService     service;

    @BeforeEach
    void setup() {
        store                 = new SimpleCommitmentStore();
        service               = new CommitmentService();
        service.store         = store;
        service.tracingConfig = new QhorusTracingConfig() {
            public boolean enabled()     {return false;}

            public boolean dispatch()    {return false;}

            public boolean commitments() {return false;}

            public boolean fanOut()      {return false;}

            public boolean ledgerWrite() {return false;}

            public boolean delivery()    {return false;}
        };
    }

    @Test
    void roleRoutedCommandSetsCapabilityTag() {
        service.open(UUID.randomUUID(), "corr-1", UUID.randomUUID(),
                     MessageType.COMMAND, "requester-1", "agent-1",
                     null, "tenant-1", "analyst");

        var commitment = store.findByCorrelationId("corr-1").orElseThrow();
        assertThat(commitment.capabilityTag()).isEqualTo("analyst");
        assertThat(commitment.tenancyId()).isEqualTo("tenant-1");
    }

    @Test
    void directAddressedCommandHasNullCapabilityTag() {
        service.open(UUID.randomUUID(), "corr-2", UUID.randomUUID(),
                     MessageType.COMMAND, "requester-1", "agent-1",
                     null, "tenant-1", null);

        var commitment = store.findByCorrelationId("corr-2").orElseThrow();
        assertThat(commitment.capabilityTag()).isNull();
    }

    @Test
    void delegateCopiesCapabilityTagAndTenancyIdToChild() {
        service.open(UUID.randomUUID(), "corr-3", UUID.randomUUID(),
                     MessageType.COMMAND, "requester-1", "agent-1",
                     null, "tenant-1", "analyst");

        service.delegate("corr-3", "agent-2");

        var all = store.findAllByCorrelationId("corr-3");
        var child = all.stream()
                       .filter(c -> c.state() == CommitmentState.OPEN)
                       .findFirst().orElseThrow();
        assertThat(child.capabilityTag()).isEqualTo("analyst");
        assertThat(child.tenancyId()).isEqualTo("tenant-1");
        assertThat(child.obligor()).isEqualTo("agent-2");
    }

    static class SimpleCommitmentStore implements CommitmentStore {
        private final Map<UUID, Commitment> byId = new LinkedHashMap<>();

        @Override
        public Commitment save(Commitment c) {
            UUID    id        = c.id() != null ? c.id() : UUID.randomUUID();
            Instant createdAt = c.createdAt() != null ? c.createdAt() : Instant.now();
            var     saved     = c.toBuilder().id(id).createdAt(createdAt).build();
            byId.put(id, saved);
            return saved;
        }

        @Override
        public Optional<Commitment> findByCorrelationId(String correlationId) {
            return byId.values().stream()
                       .filter(c -> correlationId.equals(c.correlationId()))
                       .filter(c -> c.state().isActive())
                       .findFirst();
        }

        @Override
        public List<Commitment> findAllByCorrelationId(String correlationId) {
            return byId.values().stream()
                       .filter(c -> correlationId.equals(c.correlationId()))
                       .toList();
        }

        @Override
        public Optional<Commitment> findById(UUID id)                      {return Optional.ofNullable(byId.get(id));}

        @Override
        public List<Commitment> findByIds(Collection<UUID> ids)            {return List.of();}

        @Override
        public List<Commitment> findOpenByObligor(String o, UUID ch)       {return List.of();}

        @Override
        public List<Commitment> findOpenByRequester(String r, UUID ch)     {return List.of();}

        @Override
        public List<Commitment> findByState(CommitmentState s, UUID ch)    {return List.of();}

        @Override
        public List<Commitment> findByChannel(UUID ch)                     {return List.of();}

        @Override
        public List<Commitment> findOpenByChannelId(UUID ch)               {return List.of();}

        @Override
        public List<Commitment> findExpiredBefore(Instant c)               {return List.of();}

        @Override
        public List<Commitment> findAllOpen()                              {return List.of();}

        @Override
        public List<Commitment> findByObligorInTenancy(String o, String t) {return List.of();}

        @Override
        public void deleteById(UUID id)                                    {}

        @Override
        public long deleteAll(UUID ch)                                     {return 0;}

        @Override
        public long deleteExpiredBefore(Instant c)                         {return 0;}
    }
}
