package io.casehub.qhorus.runtime.message;

import io.casehub.qhorus.persistence.memory.InMemoryMessageStore;
import io.casehub.qhorus.persistence.memory.InMemoryTopicStore;
import io.casehub.qhorus.api.message.Message;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.api.message.Topic;
import io.casehub.platform.api.identity.ActorType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class TopicMergeTest {

    private TopicService topicService;
    private InMemoryTopicStore topicStore;
    private InMemoryMessageStore messageStore;
    private final UUID channelId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        topicStore = new InMemoryTopicStore();
        messageStore = new InMemoryMessageStore();
        topicService = new TopicService();
        topicService.topicStore = topicStore;
        topicService.messageStore = messageStore;
    }

    private void addTopic(String name) {
        topicStore.put(new Topic(null, channelId, name, false, null, null, Instant.now(), null));
    }

    private void addResolvedTopic(String name) {
        topicStore.put(new Topic(null, channelId, name, true, Instant.now(), "admin", Instant.now(), null));
    }

    private void addMessage(String topic) {
        messageStore.put(Message.builder()
                .channelId(channelId).sender("agent").messageType(MessageType.STATUS)
                .actorType(ActorType.AGENT).content("msg").topic(topic)
                .build());
    }

    @Test
    void mergeUpdatesMessagesAndDeletesSource() {
        addTopic("bugs");
        addTopic("issues");
        addMessage("bugs");
        addMessage("bugs");
        addMessage("issues");

        TopicService.MergeResult result = topicService.merge(channelId, "bugs", "issues", "admin");

        assertThat(result.sourceTopic()).isEqualTo("bugs");
        assertThat(result.targetTopic()).isEqualTo("issues");
        assertThat(result.messagesUpdated()).isEqualTo(2);
        assertThat(topicStore.find(channelId, "bugs")).isEmpty();
        assertThat(topicStore.find(channelId, "issues")).isPresent();
    }

    @Test
    void mergeIntoGeneralAllowed() {
        addTopic("cleanup");
        topicService.ensureExists(channelId, "general", null);
        addMessage("cleanup");

        TopicService.MergeResult result = topicService.merge(channelId, "cleanup", "general", "admin");

        assertThat(result.messagesUpdated()).isEqualTo(1);
        assertThat(topicStore.find(channelId, "cleanup")).isEmpty();
    }

    @Test
    void mergeFromGeneralRejected() {
        topicService.ensureExists(channelId, "general", null);
        addTopic("target");

        assertThatThrownBy(() -> topicService.merge(channelId, "general", "target", "admin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("general");
    }

    @Test
    void mergeSourceNotFoundRejected() {
        addTopic("target");

        assertThatThrownBy(() -> topicService.merge(channelId, "nonexistent", "target", "admin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void mergeTargetNotFoundRejected() {
        addTopic("source");

        assertThatThrownBy(() -> topicService.merge(channelId, "source", "nonexistent", "admin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void mergeSameTopicRejected() {
        addTopic("same");

        assertThatThrownBy(() -> topicService.merge(channelId, "same", "same", "admin"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void mergeNormalisesNames() {
        addTopic("bugs");
        addTopic("issues");
        addMessage("bugs");

        TopicService.MergeResult result = topicService.merge(channelId, "  Bugs  ", "  Issues  ", "admin");

        assertThat(result.sourceTopic()).isEqualTo("Bugs");
        assertThat(result.targetTopic()).isEqualTo("Issues");
    }

    @Test
    void mergeIntoResolvedTargetPreservesResolvedState() {
        addTopic("bugs");
        addResolvedTopic("resolved-target");
        addMessage("bugs");

        topicService.merge(channelId, "bugs", "resolved-target", "admin");

        var target = topicStore.find(channelId, "resolved-target");
        assertThat(target).isPresent();
        assertThat(target.get().resolved()).isTrue();
    }
}
