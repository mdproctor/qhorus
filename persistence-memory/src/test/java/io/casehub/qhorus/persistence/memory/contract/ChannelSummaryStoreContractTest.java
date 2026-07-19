package io.casehub.qhorus.persistence.memory.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.casehub.qhorus.api.channel.ChannelSummary;

public abstract class ChannelSummaryStoreContractTest {

    protected abstract ChannelSummary save(ChannelSummary s);
    protected abstract Optional<ChannelSummary> findByChannelId(UUID channelId);
    protected abstract void deleteByChannelId(UUID channelId);
    protected abstract void reset();

    @BeforeEach
    void beforeEach() { reset(); }

    @Test
    void save_assignsId_whenNull() {
        ChannelSummary s = summary(UUID.randomUUID());
        assertThat(s.id()).isNull();
        assertThat(save(s).id()).isNotNull();
    }

    @Test
    void findByChannelId_afterSave() {
        UUID chId = UUID.randomUUID();
        save(summary(chId));
        assertThat(findByChannelId(chId)).isPresent();
        assertThat(findByChannelId(chId).get().channelId()).isEqualTo(chId);
    }

    @Test
    void findByChannelId_returnsEmpty_whenAbsent() {
        assertThat(findByChannelId(UUID.randomUUID())).isEmpty();
    }

    @Test
    void save_updatesExisting() {
        UUID chId = UUID.randomUUID();
        ChannelSummary s = save(summary(chId));
        save(s.toBuilder().content("updated").build());
        assertThat(findByChannelId(chId).get().content()).isEqualTo("updated");
    }

    @Test
    void deleteByChannelId_removes() {
        UUID chId = UUID.randomUUID();
        save(summary(chId));
        deleteByChannelId(chId);
        assertThat(findByChannelId(chId)).isEmpty();
    }

    @Test
    void deleteByChannelId_noopWhenAbsent() {
        deleteByChannelId(UUID.randomUUID());
    }

    @Test
    void save_preservesAllFields() {
        UUID chId = UUID.randomUUID();
        ChannelSummary s = save(ChannelSummary.builder(chId)
                .content("test content")
                .updatedBy("operator")
                .lastUpdatedMessageId(42L)
                .updateAfterMessages(10)
                .updateAfterSeconds(300)
                .tenancyId("278776f9-e1b0-46fb-9032-8bddebdcf9ce")
                .build());

        ChannelSummary found = findByChannelId(chId).orElseThrow();
        assertThat(found.content()).isEqualTo("test content");
        assertThat(found.updatedBy()).isEqualTo("operator");
        assertThat(found.lastUpdatedMessageId()).isEqualTo(42L);
        assertThat(found.updateAfterMessages()).isEqualTo(10);
        assertThat(found.updateAfterSeconds()).isEqualTo(300);
    }

    protected ChannelSummary summary(UUID channelId) {
        return ChannelSummary.builder(channelId)
                .content("test summary")
                .updatedBy("test")
                .tenancyId("278776f9-e1b0-46fb-9032-8bddebdcf9ce")
                .build();
    }
}
