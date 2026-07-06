package io.casehub.qhorus.postgres.broadcaster;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SelfNotificationFilterTest {

    @Test
    void recentlySent_isFiltered() {
        var filter = new SelfNotificationFilter(100);
        filter.recordSent(42L);
        assertThat(filter.wasSentLocally(42L)).isTrue();
    }

    @Test
    void unknownId_isNotFiltered() {
        var filter = new SelfNotificationFilter(100);
        assertThat(filter.wasSentLocally(99L)).isFalse();
    }

    @Test
    void evictsOldestWhenFull() {
        var filter = new SelfNotificationFilter(3);
        filter.recordSent(1L);
        filter.recordSent(2L);
        filter.recordSent(3L);
        filter.recordSent(4L); // evicts 1L

        assertThat(filter.wasSentLocally(1L)).isFalse();
        assertThat(filter.wasSentLocally(2L)).isTrue();
        assertThat(filter.wasSentLocally(3L)).isTrue();
        assertThat(filter.wasSentLocally(4L)).isTrue();
    }

    @Test
    void duplicateInsert_doesNotAffectSize() {
        var filter = new SelfNotificationFilter(3);
        filter.recordSent(1L);
        filter.recordSent(2L);
        filter.recordSent(1L); // duplicate — no size change
        filter.recordSent(3L);

        // All three fit within max size, nothing evicted
        assertThat(filter.wasSentLocally(1L)).isTrue();
        assertThat(filter.wasSentLocally(2L)).isTrue();
        assertThat(filter.wasSentLocally(3L)).isTrue();
    }

    @Test
    void sizeOneFilter_onlyKeepsLast() {
        var filter = new SelfNotificationFilter(1);
        filter.recordSent(10L);
        assertThat(filter.wasSentLocally(10L)).isTrue();

        filter.recordSent(20L);
        assertThat(filter.wasSentLocally(10L)).isFalse();
        assertThat(filter.wasSentLocally(20L)).isTrue();
    }
}
