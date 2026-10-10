package io.casehub.qhorus.runtime.instance;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.HashMap;
import java.util.Map;

public class RegistryBackedProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        Map<String, String> config = new HashMap<>();
        config.put("casehub.qhorus.instance.registry-backed", "true");
        config.put("quarkus.index-dependency.registry-inmem.group-id", "io.casehub");
        config.put("quarkus.index-dependency.registry-inmem.artifact-id", "casehub-platform-registry-inmem");
        config.put("quarkus.datasource.qhorus.db-kind", "h2");
        config.put("quarkus.datasource.qhorus.jdbc.url", "jdbc:h2:mem:test;DB_CLOSE_DELAY=-1");
        config.put("quarkus.datasource.qhorus.username", "sa");
        config.put("quarkus.datasource.qhorus.password", "");
        config.put("quarkus.datasource.qhorus.reactive", "false");
        config.put("quarkus.hibernate-orm.qhorus.database.generation", "drop-and-create");
        return config;
    }
}
