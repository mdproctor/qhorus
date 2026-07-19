package io.casehub.qhorus.runtime.channel;

import io.casehub.qhorus.api.channel.Channel;
import io.casehub.qhorus.api.channel.ChannelSummary;
import io.casehub.qhorus.api.message.Message;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.api.store.MessageStore;
import org.mockito.Mockito;
import jakarta.enterprise.event.Event;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChannelSummaryServiceTest {

    private final StubSummaryStore      summaryStore = new StubSummaryStore();
    private       ChannelSummaryService service;
    private       ChannelService        channelService;
    private       MessageStore          messageStore;

    @BeforeEach
    void setUp() {
        summaryStore.data.clear();
        channelService = Mockito.mock(ChannelService.class);
        messageStore   = Mockito.mock(MessageStore.class);

        service                = new ChannelSummaryService();
        service.summaryStore   = summaryStore;
        service.channelService = channelService;
        service.messageStore   = messageStore;
        service.hook           = ctx -> "generated summary for " + ctx.channelName();
        service.summaryEvents  = Mockito.mock(Event.class);
    }

    @Test
    void getSummary_returnsEmpty_whenNone() {
        assertThat(service.getSummary(UUID.randomUUID())).isEmpty();
    }

    @Test
    void setSummary_createsNewSummary() {
        UUID           chId = stubChannel("test-ch");
        ChannelSummary s    = service.setSummary(chId, "manual summary", "operator");
        assertThat(s.content()).isEqualTo("manual summary");
        assertThat(s.updatedBy()).isEqualTo("operator");
        assertThat(s.channelId()).isEqualTo(chId);
        assertThat(s.updatedAt()).isNotNull();
    }

    @Test
    void setSummary_updatesExisting() {
        UUID chId = stubChannel("update-ch");
        service.setSummary(chId, "first", "op1");
        ChannelSummary s = service.setSummary(chId, "second", "op2");
        assertThat(s.content()).isEqualTo("second");
        assertThat(s.updatedBy()).isEqualTo("op2");
    }

    @Test
    void setSummary_advancesCursor() {
        UUID chId = stubChannel("cursor-ch");
        Message msg = Message.builder().id(42L).channelId(chId).sender("s")
                             .messageType(MessageType.STATUS).content("x").build();
        Mockito.when(messageStore.scan(Mockito.any())).thenReturn(List.of(msg));

        ChannelSummary s = service.setSummary(chId, "manual", "op");
        assertThat(s.lastUpdatedMessageId()).isEqualTo(42L);
    }

    @Test
    void configureSummary_setsThresholds() {
        UUID           chId = stubChannel("config-ch");
        ChannelSummary s    = service.configureSummary(chId, 10, 300);
        assertThat(s.updateAfterMessages()).isEqualTo(10);
        assertThat(s.updateAfterSeconds()).isEqualTo(300);
    }

    @Test
    void configureSummary_rejectsZeroMessages() {
        UUID chId = stubChannel("reject-ch");
        assertThatThrownBy(() -> service.configureSummary(chId, 0, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void configureSummary_rejectsNegativeSeconds() {
        UUID chId = stubChannel("neg-ch");
        assertThatThrownBy(() -> service.configureSummary(chId, null, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void configureSummary_allowsNull() {
        UUID           chId = stubChannel("null-ch");
        ChannelSummary s    = service.configureSummary(chId, null, null);
        assertThat(s.updateAfterMessages()).isNull();
        assertThat(s.updateAfterSeconds()).isNull();
    }

    @Test
    void triggerUpdate_invokesHookAndStores() {
        UUID chId = stubChannel("trigger-ch");
        service.configureSummary(chId, null, null);
        Mockito.when(messageStore.count(Mockito.any())).thenReturn(5L);
        Mockito.when(messageStore.scan(Mockito.any())).thenReturn(List.of());

        Optional<ChannelSummary> result = service.triggerUpdate(chId);
        assertThat(result).isPresent();
        assertThat(result.get().content()).contains("generated summary for trigger-ch");
    }

    @Test
    void triggerUpdate_returnsEmpty_whenNoSummaryConfigured() {
        assertThat(service.triggerUpdate(UUID.randomUUID())).isEmpty();
    }

    @Test
    void deleteSummary_removes() {
        UUID chId = stubChannel("del-ch");
        service.setSummary(chId, "to delete", "op");
        service.deleteSummary(chId);
        assertThat(service.getSummary(chId)).isEmpty();
    }

    @Test
    void setSummary_throwsForUnknownChannel() {
        UUID unknown = UUID.randomUUID();
        Mockito.when(channelService.findById(unknown)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.setSummary(unknown, "x", "op"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private UUID stubChannel(String name) {
        UUID id = UUID.randomUUID();
        Channel ch = Channel.builder(name).id(id)
                            .tenancyId("278776f9-e1b0-46fb-9032-8bddebdcf9ce")
                            .createdAt(Instant.now()).lastActivityAt(Instant.now()).build();
        Mockito.when(channelService.findById(id)).thenReturn(Optional.of(ch));
        Mockito.when(messageStore.scan(Mockito.any())).thenReturn(List.of());
        return id;
    }

    static class StubSummaryStore implements io.casehub.qhorus.api.store.ChannelSummaryStore {
        final java.util.Map<UUID, ChannelSummary> data = new java.util.concurrent.ConcurrentHashMap<>();

        @Override
        public ChannelSummary save(ChannelSummary s) {
            ChannelSummary saved = s.id() == null ? s.toBuilder().id(UUID.randomUUID()).build() : s;
            data.put(saved.channelId(), saved);
            return saved;
        }

        @Override
        public Optional<ChannelSummary> findByChannelId(UUID channelId) {return Optional.ofNullable(data.get(channelId));}

        @Override
        public void deleteByChannelId(UUID channelId) {data.remove(channelId);}
    }
}
