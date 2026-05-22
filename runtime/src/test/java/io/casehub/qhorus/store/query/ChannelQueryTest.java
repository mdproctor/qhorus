package io.casehub.qhorus.store.query;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.casehub.qhorus.runtime.channel.Channel;
import io.casehub.qhorus.runtime.store.query.ChannelQuery;

class ChannelQueryTest {

    @Test
    void all_matchesAnyChannel() {
        Channel ch = new Channel();
        ch.paused = false;
        assertTrue(ChannelQuery.all().matches(ch));
    }

    @Test
    void all_matchesPausedChannel() {
        Channel ch = new Channel();
        ch.paused = true;
        assertTrue(ChannelQuery.all().matches(ch));
    }

    @Test
    void pausedOnly_matchesPausedChannel() {
        Channel ch = new Channel();
        ch.paused = true;
        assertTrue(ChannelQuery.pausedOnly().matches(ch));
    }

    @Test
    void pausedOnly_doesNotMatchActiveChannel() {
        Channel ch = new Channel();
        ch.paused = false;
        assertFalse(ChannelQuery.pausedOnly().matches(ch));
    }

    @Test
    void bySemantic_matchesCorrectSemantic() {
        Channel ch = new Channel();
        ch.semantic = ChannelSemantic.APPEND;
        assertTrue(ChannelQuery.bySemantic(ChannelSemantic.APPEND).matches(ch));
        assertFalse(ChannelQuery.bySemantic(ChannelSemantic.COLLECT).matches(ch));
    }

    @Test
    void byName_matchesGlobPattern() {
        Channel ch = new Channel();
        ch.name = "agent-events";
        assertTrue(ChannelQuery.byName("agent-*").matches(ch));
        assertFalse(ChannelQuery.byName("task-*").matches(ch));
    }

    @Test
    void byName_doesNotMatchNullName() {
        Channel ch = new Channel();
        ch.name = null;
        assertFalse(ChannelQuery.byName("agent-*").matches(ch));
    }

    @Test
    void builder_combinesPredicates() {
        Channel ch = new Channel();
        ch.paused = true;
        ch.semantic = ChannelSemantic.BARRIER;

        ChannelQuery match = ChannelQuery.builder().paused(true).semantic(ChannelSemantic.BARRIER).build();
        assertTrue(match.matches(ch));

        ChannelQuery noMatch = ChannelQuery.builder().paused(true).semantic(ChannelSemantic.APPEND).build();
        assertFalse(noMatch.matches(ch));
    }

    @Test
    void byNamePrefix_matches_channelWithMatchingPrefix() {
        Channel ch = new Channel();
        ch.name = "case-123/work";
        assertTrue(ChannelQuery.byNamePrefix("case-123").matches(ch));
    }

    @Test
    void byNamePrefix_matches_exactPrefixEquality() {
        Channel ch = new Channel();
        ch.name = "case-123";
        assertTrue(ChannelQuery.byNamePrefix("case-123").matches(ch));
    }

    @Test
    void byNamePrefix_doesNotMatch_channelWithDifferentPrefix() {
        Channel ch = new Channel();
        ch.name = "case-456/work";
        assertFalse(ChannelQuery.byNamePrefix("case-123").matches(ch));
    }

    @Test
    void byNamePrefix_doesNotMatch_nullName() {
        Channel ch = new Channel();
        ch.name = null;
        assertFalse(ChannelQuery.byNamePrefix("case-").matches(ch));
    }

    @Test
    void byNamePrefix_combinedWithSemantic_filtersCorrectly() {
        Channel match = new Channel();
        match.name = "case-99/work";
        match.semantic = ChannelSemantic.APPEND;

        Channel wrongSemantic = new Channel();
        wrongSemantic.name = "case-99/observe";
        wrongSemantic.semantic = ChannelSemantic.COLLECT;

        ChannelQuery q = ChannelQuery.builder().namePrefix("case-99").semantic(ChannelSemantic.APPEND).build();
        assertTrue(q.matches(match));
        assertFalse(q.matches(wrongSemantic));
    }

    @Test
    void toBuilder_roundTrips() {
        ChannelQuery original = ChannelQuery.builder().paused(false).semantic(ChannelSemantic.COLLECT).build();
        ChannelQuery copy = original.toBuilder().build();

        Channel ch = new Channel();
        ch.paused = false;
        ch.semantic = ChannelSemantic.COLLECT;

        assertTrue(original.matches(ch));
        assertTrue(copy.matches(ch));
    }

    @Test
    void toBuilder_roundTrips_withNamePrefix() {
        ChannelQuery original = ChannelQuery.byNamePrefix("case-123/");
        ChannelQuery copy = original.toBuilder().build();

        Channel match = new Channel();
        match.name = "case-123/work";

        Channel noMatch = new Channel();
        noMatch.name = "case-456/work";

        assertTrue(copy.matches(match));
        assertFalse(copy.matches(noMatch));
    }
}
