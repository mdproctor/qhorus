package io.casehub.qhorus.service;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import jakarta.inject.Inject;

import io.casehub.platform.api.identity.ActorTypeResolver;
import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.casehub.qhorus.api.message.DispatchResult;
import io.casehub.qhorus.api.message.MessageDispatch;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.channel.Channel;
import io.casehub.qhorus.runtime.channel.ChannelService;
import io.casehub.qhorus.runtime.instance.InstanceService;
import io.casehub.qhorus.runtime.message.Message;
import io.casehub.qhorus.runtime.message.MessageService;
import io.casehub.qhorus.runtime.store.ChannelStore;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
@TestTransaction
class MessageServiceTest extends MessageServiceContractTest {

    @Inject
    MessageService svc;

    @Inject
    ChannelService channelService;

    @Inject
    ChannelStore channelStore;

    @Inject
    InstanceService instanceService;

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

    @Override
    protected UUID persistChannel(boolean paused, String allowedWriters,
            Integer rateLimitPerInstance, Set<MessageType> allowedTypes, ChannelSemantic semantic) {
        Channel ch = new Channel();
        ch.id = UUID.randomUUID();
        ch.name = "contract-" + ch.id;
        ch.semantic = semantic;
        ch.paused = paused;
        ch.allowedWriters = allowedWriters;
        ch.rateLimitPerInstance = rateLimitPerInstance;
        ch.allowedTypes = MessageType.serializeTypes(allowedTypes);
        return channelStore.put(ch).id;
    }

    @Override
    protected void persistInstance(String instanceId, List<String> capabilities) {
        instanceService.register(instanceId, "contract-test agent", capabilities);
    }
}
