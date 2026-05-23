package io.casehub.qhorus.service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Disabled;

import io.casehub.platform.api.identity.ActorTypeResolver;
import io.casehub.qhorus.api.message.DispatchResult;
import io.casehub.qhorus.api.message.MessageDispatch;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.message.Message;
import io.casehub.qhorus.runtime.message.ReactiveMessageService;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

@Disabled("ReactiveMessageService calls Panache.withTransaction() — requires reactive datasource.")
@QuarkusTest
@TestProfile(ReactiveTestProfile.class)
class ReactiveMessageServiceTest extends MessageServiceContractTest {

    @Inject
    ReactiveMessageService svc;

    @Override
    protected DispatchResult send(UUID channelId, String sender, MessageType type,
            String content, String correlationId, Long inReplyTo) {
        return svc.dispatch(MessageDispatch.builder()
                .channelId(channelId)
                .sender(sender)
                .type(type)
                .content(content)
                .correlationId(correlationId)
                .inReplyTo(inReplyTo)
                .actorType(ActorTypeResolver.resolve(sender))
                .build()).await().indefinitely();
    }

    @Override
    protected Optional<Message> findById(Long id) {
        return svc.findById(id).await().indefinitely();
    }

    @Override
    protected List<Message> pollAfter(UUID channelId, Long afterId, int limit) {
        return svc.pollAfter(channelId, afterId, limit).await().indefinitely();
    }
}
