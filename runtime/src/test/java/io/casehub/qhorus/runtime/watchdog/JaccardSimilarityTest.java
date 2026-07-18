package io.casehub.qhorus.runtime.watchdog;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class JaccardSimilarityTest {

    @Test
    void identicalStrings_returnsOne() {
        assertThat(JaccardSimilarity.similarity("hello world", "hello world"))
                .isCloseTo(1.0, within(0.001));
    }

    @Test
    void disjointStrings_returnsZero() {
        assertThat(JaccardSimilarity.similarity("alpha beta", "gamma delta"))
                .isCloseTo(0.0, within(0.001));
    }

    @Test
    void partialOverlap_returnsCorrectRatio() {
        assertThat(JaccardSimilarity.similarity("hello world", "hello there"))
                .isCloseTo(1.0 / 3.0, within(0.001));
    }

    @Test
    void caseInsensitive() {
        assertThat(JaccardSimilarity.similarity("Hello World", "hello world"))
                .isCloseTo(1.0, within(0.001));
    }

    @Test
    void nullInput_returnsZero() {
        assertThat(JaccardSimilarity.similarity(null, "hello")).isCloseTo(0.0, within(0.001));
        assertThat(JaccardSimilarity.similarity("hello", null)).isCloseTo(0.0, within(0.001));
    }

    @Test
    void bothEmpty_returnsOne() {
        assertThat(JaccardSimilarity.similarity("", "")).isCloseTo(1.0, within(0.001));
    }

    @Test
    void oneEmpty_returnsZero() {
        assertThat(JaccardSimilarity.similarity("", "hello")).isCloseTo(0.0, within(0.001));
    }

    @Test
    void punctuationStripping_reducesStructuredContentFalsePositives() {
        String json1 = "{\"action\": \"deploy\", \"target\": \"prod\"}";
        String json2 = "{\"action\": \"rollback\", \"target\": \"staging\"}";
        assertThat(JaccardSimilarity.similarity(json1, json2))
                .isLessThan(0.7);
    }
}
