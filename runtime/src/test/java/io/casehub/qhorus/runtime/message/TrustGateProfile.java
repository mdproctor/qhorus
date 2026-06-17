package io.casehub.qhorus.runtime.message;

import java.util.HashMap;
import java.util.Map;

import io.quarkus.test.junit.QuarkusTestProfile;

/**
 * Test profile that enables the trust gate at minObligorTrust=0.5.
 * Restarts the Quarkus context — datasource block is required.
 */
public class TrustGateProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        Map<String, String> config = new HashMap<>();
        config.put("casehub.qhorus.commitment.min-obligor-trust", "0.5");
        // Route the LedgerPersistenceUnit EntityManager to the qhorus PU, where
        // ActorTrustScore (and its named queries) are registered. Without this,
        // JpaActorTrustScoreRepository falls back to the default PU whose entity scan
        // only covers io.casehub.qhorus.runtime.config — no ActorTrustScore named queries.
        config.put("casehub.ledger.datasource", "qhorus");
        // JpaActorTrustScoreRepository became @Alternative in ledger#143 — must be explicitly activated
        config.put("quarkus.arc.selected-alternatives",
                "io.casehub.ledger.runtime.repository.jpa.JpaActorTrustScoreRepository");
        config.put("quarkus.datasource.qhorus.db-kind", "h2");
        config.put("quarkus.datasource.qhorus.jdbc.url", "jdbc:h2:mem:test;DB_CLOSE_DELAY=-1");
        config.put("quarkus.datasource.qhorus.username", "sa");
        config.put("quarkus.datasource.qhorus.password", "");
        config.put("quarkus.datasource.qhorus.reactive", "false");
        config.put("quarkus.hibernate-orm.qhorus.database.generation", "drop-and-create");
        return config;
    }
}
