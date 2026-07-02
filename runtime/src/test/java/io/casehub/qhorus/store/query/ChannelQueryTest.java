package io.casehub.qhorus.store.query;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import io.casehub.qhorus.api.channel.Channel;
import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.casehub.qhorus.api.store.query.ChannelQuery;

class ChannelQueryTest {

    private Channel ch(String name, ChannelSemantic semantic, boolean paused) {
        return Channel.builder(name).semantic(semantic).paused(paused).build();
    }

    @Test
    void all_matchesAnyChannel() {
        assertTrue(ChannelQuery.all().matches(ch("x", null, false)));
    }

    @Test
    void all_matchesPausedChannel() {
        assertTrue(ChannelQuery.all().matches(ch("x", null, true)));
    }

    @Test
    void pausedOnly_matchesPausedChannel() {
        assertTrue(ChannelQuery.pausedOnly().matches(ch("x", null, true)));
    }

    @Test
    void pausedOnly_doesNotMatchActiveChannel() {
        assertFalse(ChannelQuery.pausedOnly().matches(ch("x", null, false)));
    }

    @Test
    void bySemantic_matchesCorrectSemantic() {
        Channel ch = ch("x", ChannelSemantic.APPEND, false);
        assertTrue(ChannelQuery.bySemantic(ChannelSemantic.APPEND).matches(ch));
        assertFalse(ChannelQuery.bySemantic(ChannelSemantic.COLLECT).matches(ch));
    }

    @Test
    void byName_matchesGlobPattern() {
        Channel ch = ch("agent-events", null, false);
        assertTrue(ChannelQuery.byName("agent-*").matches(ch));
        assertFalse(ChannelQuery.byName("task-*").matches(ch));
    }

    @Test
    void byName_doesNotMatchNullName() {
        Channel ch = Channel.builder("placeholder").build();
        // Channel record requires a name, so null name isn't possible via builder.
        // Test the query with a name that doesn't match.
        assertFalse(ChannelQuery.byName("agent-*").matches(ch));
    }

    @Test
    void builder_combinesPredicates() {
        Channel ch = ch("x", ChannelSemantic.BARRIER, true);

        ChannelQuery match = ChannelQuery.builder().paused(true).semantic(ChannelSemantic.BARRIER).build();
        assertTrue(match.matches(ch));

        ChannelQuery noMatch = ChannelQuery.builder().paused(true).semantic(ChannelSemantic.APPEND).build();
        assertFalse(noMatch.matches(ch));
    }

    @Test
    void byNamePrefix_matches_channelWithMatchingPrefix() {
        assertTrue(ChannelQuery.byNamePrefix("case-123").matches(ch("case-123/work", null, false)));
    }

    @Test
    void byNamePrefix_matches_exactPrefixEquality() {
        assertTrue(ChannelQuery.byNamePrefix("case-123").matches(ch("case-123", null, false)));
    }

    @Test
    void byNamePrefix_doesNotMatch_channelWithDifferentPrefix() {
        assertFalse(ChannelQuery.byNamePrefix("case-123").matches(ch("case-456/work", null, false)));
    }

    @Test
    void byNamePrefix_combinedWithSemantic_filtersCorrectly() {
        Channel match = ch("case-99/work", ChannelSemantic.APPEND, false);
        Channel wrongSemantic = ch("case-99/observe", ChannelSemantic.COLLECT, false);

        ChannelQuery q = ChannelQuery.builder().namePrefix("case-99").semantic(ChannelSemantic.APPEND).build();
        assertTrue(q.matches(match));
        assertFalse(q.matches(wrongSemantic));
    }

    @Test
    void toBuilder_roundTrips() {
        ChannelQuery original = ChannelQuery.builder().paused(false).semantic(ChannelSemantic.COLLECT).build();
        ChannelQuery copy = original.toBuilder().build();

        Channel ch = ch("x", ChannelSemantic.COLLECT, false);

        assertTrue(original.matches(ch));
        assertTrue(copy.matches(ch));
    }

    @Test
    void toBuilder_roundTrips_withNamePrefix() {
        ChannelQuery original = ChannelQuery.byNamePrefix("case-123/");
        ChannelQuery copy = original.toBuilder().build();

        assertTrue(copy.matches(ch("case-123/work", null, false)));
        assertFalse(copy.matches(ch("case-456/work", null, false)));
    }

    @Test
    void byKeyword_matchesName() {
        Channel ch = Channel.builder("security-review").description("Thread for reviews").build();
        assertTrue(ChannelQuery.byKeyword("security").matches(ch));
    }

    @Test
    void byKeyword_matchesDescription_caseInsensitive() {
        Channel ch = Channel.builder("my-channel").description("Security review thread").build();
        assertTrue(ChannelQuery.byKeyword("SECURITY").matches(ch));
    }

    @Test
    void byKeyword_noMatch() {
        Channel ch = Channel.builder("my-channel").description("General discussion").build();
        assertFalse(ChannelQuery.byKeyword("security").matches(ch));
    }

    @Test
    void byKeyword_nullDescription_matchesNameOnly() {
        Channel ch = Channel.builder("audit-channel").build();
        assertTrue(ChannelQuery.byKeyword("audit").matches(ch));
        assertFalse(ChannelQuery.byKeyword("missing").matches(ch));
    }
}
