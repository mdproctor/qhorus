package io.casehub.qhorus.runtime.audit;

import io.casehub.qhorus.api.spi.CommitmentContext;
import io.casehub.qhorus.api.store.DataStore;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class EvidentialCheckerV1V4Test {

    @Test
    void done_withArtefactPresent_noViolation() {
        UUID              artefactId = UUID.randomUUID();
        EvidentialChecker checker    = new EvidentialChecker();
        checker.dataStore = stubDataStore(artefactId);
        var ctx = new CommitmentContext("corr", UUID.randomUUID(), "ch", UUID.randomUUID(), null,
                                        artefactId, null, null);
        assertThat(checker.checkObligation("DONE", ctx)).isEmpty();
    }

    @Test
    void done_withGhostArtefact_idfViolation() {
        UUID              artefactId = UUID.randomUUID();
        EvidentialChecker checker    = new EvidentialChecker();
        checker.dataStore = stubDataStore(null);
        var ctx = new CommitmentContext("corr", UUID.randomUUID(), "ch", UUID.randomUUID(), null,
                                        artefactId, null, null);
        var violations = checker.checkObligation("DONE", ctx);
        assertThat(violations).hasSize(1);
        assertThat(violations.get(0).invariant()).isEqualTo("I_df");
        assertThat(violations.get(0).description()).contains("non-existent artefact");
    }

    @Test
    void done_withArtefactUuidNull_noV1Check() {
        EvidentialChecker checker = new EvidentialChecker();
        var ctx = new CommitmentContext("corr", UUID.randomUUID(), "ch", UUID.randomUUID(), null,
                                        null, null, null);
        assertThat(checker.checkObligation("DONE", ctx)).isEmpty();
    }

    @Test
    void done_withBothArtefactAndToken_reportsAllViolations() {
        UUID              artefactId = UUID.randomUUID();
        EvidentialChecker checker    = new EvidentialChecker();
        checker.dataStore = stubDataStore(null);
        var ctx = new CommitmentContext("corr", UUID.randomUUID(), "ch", UUID.randomUUID(), null,
                                        artefactId, "token-xyz", "no match here");
        var violations = checker.checkObligation("DONE", ctx);
        assertThat(violations).hasSize(2);
        assertThat(violations).extracting(BenchmarkViolation::invariant)
                              .containsExactlyInAnyOrder("I_df", "I_ec");
    }

    @Test
    void failure_withArtefactUuid_noEvidentialCheck() {
        EvidentialChecker checker = new EvidentialChecker();
        var ctx = new CommitmentContext("corr", UUID.randomUUID(), "ch", UUID.randomUUID(), null,
                                        UUID.randomUUID(), "token", "content");
        assertThat(checker.checkObligation("FAILURE", ctx)).isEmpty();
    }

    private static DataStore stubDataStore(UUID presentId) {
        return new DataStore() {
            @Override
            public io.casehub.qhorus.api.data.SharedData put(io.casehub.qhorus.api.data.SharedData d) {throw new UnsupportedOperationException();}

            @Override
            public Optional<io.casehub.qhorus.api.data.SharedData> find(UUID id) {
                if (presentId != null && id.equals(presentId)) {
                    return Optional.of(io.casehub.qhorus.api.data.SharedData.builder("k").id(presentId).createdBy("test").content("data").build());
                }
                return Optional.empty();
            }

            @Override
            public java.util.List<io.casehub.qhorus.api.data.SharedData> findByIds(java.util.Collection<UUID> ids)               {throw new UnsupportedOperationException();}

            @Override
            public Optional<io.casehub.qhorus.api.data.SharedData> findByKey(String key)                                         {throw new UnsupportedOperationException();}

            @Override
            public java.util.List<io.casehub.qhorus.api.data.SharedData> scan(io.casehub.qhorus.api.store.query.DataQuery query) {throw new UnsupportedOperationException();}

            @Override
            public io.casehub.qhorus.api.data.ArtefactClaim putClaim(io.casehub.qhorus.api.data.ArtefactClaim claim)             {throw new UnsupportedOperationException();}

            @Override
            public void deleteClaim(UUID artefactId, UUID instanceId)                                                            {throw new UnsupportedOperationException();}

            @Override
            public int countClaims(UUID artefactId)                                                                              {throw new UnsupportedOperationException();}

            @Override
            public void delete(UUID id)                                                                                          {throw new UnsupportedOperationException();}
        };
    }
}
