package io.casehub.qhorus.runtime.store.jpa;

import io.casehub.qhorus.api.store.query.MessageQuery;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MessageQueryJpqlTopicTest {

    @Test
    void from_withTopic_addsCaseInsensitivePredicate() {
        MessageQuery q = MessageQuery.builder().topic("design").build();
        MessageQueryJpql jpql = MessageQueryJpql.from(q);

        assertThat(jpql.where()).contains("LOWER(topic) = LOWER(");
        assertThat(jpql.params()).contains("design");
    }

    @Test
    void from_withoutTopic_noTopicPredicate() {
        MessageQuery q = MessageQuery.builder().build();
        MessageQueryJpql jpql = MessageQueryJpql.from(q);

        assertThat(jpql.where()).doesNotContain("topic");
    }

    @Test
    void fromTenanted_withTopic_addsCaseInsensitivePredicate() {
        MessageQuery q = MessageQuery.builder().topic("Review").build();
        MessageQueryJpql jpql = MessageQueryJpql.from(q, "tenant-1");

        assertThat(jpql.where()).contains("LOWER(topic) = LOWER(");
        assertThat(jpql.params()).contains("Review");
    }

    @Test
    void fromTenanted_withoutTopic_noTopicPredicate() {
        MessageQuery q = MessageQuery.builder().build();
        MessageQueryJpql jpql = MessageQueryJpql.from(q, "tenant-1");

        assertThat(jpql.where()).doesNotContain("topic");
    }
}
