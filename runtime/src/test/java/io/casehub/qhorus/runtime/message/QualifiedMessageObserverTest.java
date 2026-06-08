package io.casehub.qhorus.runtime.message;

import static org.assertj.core.api.Assertions.*;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Qualifier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.gateway.MessageObserver;
import io.casehub.qhorus.api.gateway.MessageReceivedEvent;
import io.casehub.qhorus.api.message.MessageDispatch;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.channel.Channel;
import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.casehub.qhorus.runtime.store.ChannelStore;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;

/**
 * Verifies that MessageObserver implementations with custom CDI qualifiers are
 * discovered and invoked by MessageService. Without @Any on the Instance<MessageObserver>
 * injection point, qualified beans are silently excluded (Refs qhorus#259).
 */
@QuarkusTest
class QualifiedMessageObserverTest {

    @Qualifier
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER})
    public @interface TestObserverMarker {}

    @TestObserverMarker
    @ApplicationScoped
    public static class QualifiedObserver implements MessageObserver {
        public static final List<MessageReceivedEvent> received = new CopyOnWriteArrayList<>();

        @Override
        public void onMessage(MessageReceivedEvent event) {
            received.add(event);
        }
    }

    @ApplicationScoped
    public static class ChannelFilteredObserver implements MessageObserver {
        public static volatile String targetChannel = null;
        public static final List<MessageReceivedEvent> received = new CopyOnWriteArrayList<>();

        @Override
        public java.util.Set<String> channels() {
            return targetChannel != null ? java.util.Set.of(targetChannel) : java.util.Set.of();
        }

        @Override
        public void onMessage(MessageReceivedEvent event) {
            received.add(event);
        }
    }

    @Inject ChannelStore channelStore;
    @Inject MessageService messageService;

    @BeforeEach
    void clearRecorded() {
        QualifiedObserver.received.clear();
        ChannelFilteredObserver.received.clear();
        ChannelFilteredObserver.targetChannel = null;
    }

    private UUID createAndCommitChannel(String name) {
        UUID[] id = {null};
        QuarkusTransaction.requiringNew().run(() -> {
            Channel ch = new Channel();
            ch.id = UUID.randomUUID();
            ch.name = name;
            ch.semantic = ChannelSemantic.APPEND;
            channelStore.put(ch);
            id[0] = ch.id;
        });
        return id[0];
    }

    @Test
    void qualifiedObserver_isInvoked_afterDispatch() {
        UUID channelId = createAndCommitChannel("test-qual-obs-" + UUID.randomUUID());

        // Dispatch in committed TX so post-commit Synchronization fires QualifiedObserver.onMessage()
        QuarkusTransaction.requiringNew().run(() ->
            messageService.dispatch(MessageDispatch.builder()
                    .channelId(channelId).sender("agent")
                    .type(MessageType.STATUS).content("hello")
                    .actorType(ActorType.AGENT).build()));

        assertThat(QualifiedObserver.received).hasSize(1);
        assertThat(QualifiedObserver.received.get(0).content()).isEqualTo("hello");
    }

    @Test
    void channelFilteredObserver_receivesMatchingChannel_notOthers() {
        String matchChannel = "test-filter-match-" + UUID.randomUUID();
        String otherChannel = "test-filter-other-" + UUID.randomUUID();
        UUID matchId = createAndCommitChannel(matchChannel);
        UUID otherId = createAndCommitChannel(otherChannel);

        ChannelFilteredObserver.targetChannel = matchChannel;

        QuarkusTransaction.requiringNew().run(() ->
            messageService.dispatch(MessageDispatch.builder()
                    .channelId(matchId).sender("agent")
                    .type(MessageType.STATUS).content("in-scope")
                    .actorType(ActorType.AGENT).build()));

        QuarkusTransaction.requiringNew().run(() ->
            messageService.dispatch(MessageDispatch.builder()
                    .channelId(otherId).sender("agent")
                    .type(MessageType.STATUS).content("out-of-scope")
                    .actorType(ActorType.AGENT).build()));

        assertThat(ChannelFilteredObserver.received).hasSize(1);
        assertThat(ChannelFilteredObserver.received.get(0).content()).isEqualTo("in-scope");
    }
}
