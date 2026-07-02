package io.casehub.qhorus.store.query;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import io.casehub.qhorus.api.data.SharedData;
import io.casehub.qhorus.api.store.query.DataQuery;

class DataQueryTest {

    private SharedData sharedData(String createdBy, boolean complete) {
        return SharedData.builder("k").createdBy(createdBy).complete(complete).build();
    }

    @Test
    void all_matchesAnyData() {
        assertTrue(DataQuery.all().matches(sharedData("agent-1", false)));
    }

    @Test
    void completeOnly_matchesCompleteData() {
        assertTrue(DataQuery.completeOnly().matches(sharedData("agent-1", true)));
    }

    @Test
    void completeOnly_doesNotMatchIncompleteData() {
        assertFalse(DataQuery.completeOnly().matches(sharedData("agent-1", false)));
    }

    @Test
    void byCreator_matchesCorrectCreator() {
        SharedData d = sharedData("agent-1", true);
        assertTrue(DataQuery.byCreator("agent-1").matches(d));
        assertFalse(DataQuery.byCreator("agent-2").matches(d));
    }

    @Test
    void byCreator_doesNotMatchNullCreator() {
        assertFalse(DataQuery.byCreator("agent-1").matches(sharedData(null, true)));
    }

    @Test
    void builder_combinesPredicates() {
        DataQuery q = DataQuery.builder().createdBy("agent-1").complete(true).build();

        assertTrue(q.matches(sharedData("agent-1", true)));
        assertFalse(q.matches(sharedData("agent-1", false)));
        assertFalse(q.matches(sharedData("agent-2", true)));
    }

    @Test
    void toBuilder_roundTrips() {
        DataQuery original = DataQuery.builder().createdBy("agent-x").complete(false).build();
        DataQuery copy = original.toBuilder().build();

        SharedData d = sharedData("agent-x", false);
        assertTrue(original.matches(d));
        assertTrue(copy.matches(d));
    }
}
