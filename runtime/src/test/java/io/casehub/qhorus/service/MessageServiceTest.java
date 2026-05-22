package io.casehub.qhorus.service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.inject.Inject;

import io.casehub.platform.api.identity.ActorType;
import io.casehub.platform.api.identity.ActorTypeResolver;
import io.casehub.qhorus.api.message.DispatchResult;
import io.casehub.qhorus.api.message.MessageDispatch;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.message.Message;
import io.casehub.qhorus.runtime.message.MessageService;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
@TestTransaction
class MessageServiceTest extends MessageServiceContractTest {

    @Inject
    MessageService svc;

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
                .build());
    }

    @Override
    protected Optional<Message> findById(Long id) {
        return svc.findById(id);
    }

    @Override
    protected List<Message> pollAfter(UUID channelId, Long afterId, int limit) {
        return svc.pollAfter(channelId, afterId, limit);
    }
}
