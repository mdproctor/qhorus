package io.casehub.qhorus.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import io.casehub.platform.api.identity.ActorType;
import io.casehub.platform.api.identity.ActorTypeResolver;
import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.casehub.qhorus.api.message.DispatchResult;
import io.casehub.qhorus.api.message.MessageDispatch;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.channel.Channel;
import io.casehub.qhorus.runtime.channel.ChannelService;
import io.casehub.qhorus.runtime.message.Message;
import io.casehub.qhorus.runtime.message.ReactiveMessageService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

@Disabled("Requires PostgreSQL DevServices — reactive Panache.withTransaction() cannot run on H2")
@QuarkusTest
@TestProfile(ReactiveTestProfile.class)
class ReactiveMessageServiceTest extends MessageServiceContractTest {

    @Inject
    ReactiveMessageService svc;

    /**
     * Blocking ChannelService — always available regardless of
     * {@code casehub.qhorus.reactive.enabled}. Used to create channels in test
     * setup via a committed JTA transaction so the reactive dispatch can see them.
     */
    @Inject
    ChannelService channelService;

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

    /**
     * Creates a channel via the blocking {@link ChannelService} in a committed
     * {@code REQUIRES_NEW} transaction so the reactive dispatch sees it. This
     * avoids the Vert.x context requirement of {@code Panache.withTransaction()}.
     */
    @Override
    protected UUID persistChannel(boolean paused, String allowedWriters,
            Integer rateLimitPerInstance, String allowedTypes, ChannelSemantic semantic) {
        UUID[] id = new UUID[1];
        QuarkusTransaction.requiringNew().run(() -> {
            Channel ch = channelService.create(
                    "contract-reactive-" + UUID.randomUUID(),
                    "contract test channel",
                    semantic,
                    /* barrierContributors */ null,
                    allowedWriters,
                    /* adminInstances */ null,
                    /* rateLimitPerChannel */ null,
                    rateLimitPerInstance,
                    allowedTypes);
            if (paused) {
                ch.paused = true;
            }
            id[0] = ch.id;
        });
        return id[0];
    }

    /**
     * Instance registration is not needed for the enforcement tests exercised
     * here — ACL tests use sender names directly, not capability tags. No-op.
     */
    @Override
    protected void persistInstance(String instanceId, List<String> capabilities) {
        // No-op: reactive enforcement tests rely on sender-name ACL matching only.
    }

    /**
     * Reactive parity for the blocking {@code dispatch_command_with_deadline_persists_deadline}
     * test in {@code MessageDispatchIntegrationTest}. Verifies that
     * {@link ReactiveMessageService#dispatch} correctly assigns the deadline from
     * {@link MessageDispatch} to the persisted {@link Message}.
     *
     * <p>Runs under the {@code reactive-pg} profile with PostgreSQL DevServices. Refs #198.
     */
    @Test
    void dispatch_command_with_deadline_persists_deadline() {
        final UUID channelId = UUID.randomUUID();
        final Instant deadline = Instant.now().plus(Duration.ofHours(1)).truncatedTo(ChronoUnit.MILLIS);

        final DispatchResult result = svc.dispatch(MessageDispatch.builder()
                .channelId(channelId).sender("orchestrator").type(MessageType.COMMAND)
                .content("do task").correlationId("corr-deadline-" + UUID.randomUUID())
                .deadline(deadline)
                .actorType(ActorType.SYSTEM).build()).await().indefinitely();

        assertThat(svc.findById(result.messageId()).await().indefinitely())
                .isPresent()
                .hasValueSatisfying(m -> assertThat(m.deadline).isEqualTo(deadline));
    }
}
