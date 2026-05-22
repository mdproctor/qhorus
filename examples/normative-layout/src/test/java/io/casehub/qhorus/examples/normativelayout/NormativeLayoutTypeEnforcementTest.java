package io.casehub.qhorus.examples.normativelayout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.casehub.platform.api.identity.ActorTypeResolver;
import io.casehub.qhorus.api.message.DispatchResult;
import io.casehub.qhorus.api.message.MessageDispatch;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.api.message.MessageTypeViolationException;
import io.casehub.qhorus.runtime.channel.ChannelService;
import io.casehub.qhorus.runtime.data.DataService;
import io.casehub.qhorus.runtime.instance.InstanceService;
import io.casehub.qhorus.runtime.message.MessageService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class NormativeLayoutTypeEnforcementTest {

    @Inject
    ChannelService channelService;
    @Inject
    InstanceService instanceService;
    @Inject
    MessageService messageService;
    @Inject
    DataService dataService;

    private SecureCodeReviewScenario scenario(String prefix) {
        return new SecureCodeReviewScenario(prefix + System.nanoTime(),
                channelService, instanceService, messageService, dataService);
    }

    @Test
    void observeChannel_rejectsQuery_serverSide() {
        SecureCodeReviewScenario s = scenario("enf-1-");
        QuarkusTransaction.requiringNew().run(s::setupChannels);

        assertThatThrownBy(() -> QuarkusTransaction.requiringNew().run(() -> {
            messageService.dispatch(                    MessageDispatch.builder()
                    .channelId(s.observeChannel().id)
                    .sender("agent-x")
                    .type(MessageType.QUERY)
                    .content("some query")
                    .correlationId("corr-" + System.nanoTime())
                    .actorType(ActorTypeResolver.resolve("agent-x"))
                    .build());
        })).isInstanceOf(MessageTypeViolationException.class);
    }

    @Test
    void observeChannel_rejectsCommand_serverSide() {
        SecureCodeReviewScenario s = scenario("enf-2-");
        QuarkusTransaction.requiringNew().run(s::setupChannels);

        assertThatThrownBy(() -> QuarkusTransaction.requiringNew().run(() -> {
            messageService.dispatch(                    MessageDispatch.builder()
                    .channelId(s.observeChannel().id)
                    .sender("agent-x")
                    .type(MessageType.COMMAND)
                    .content("some command")
                    .correlationId("corr-" + System.nanoTime())
                    .actorType(ActorTypeResolver.resolve("agent-x"))
                    .build());
        })).isInstanceOf(MessageTypeViolationException.class);
    }

    @Test
    void observeChannel_permitsEvent_serverSide() {
        SecureCodeReviewScenario s = scenario("enf-3-");
        QuarkusTransaction.requiringNew().run(s::setupChannels);

        DispatchResult[] result = new DispatchResult[1];
        QuarkusTransaction.requiringNew().run(() -> {
            result[0] = messageService.dispatch(                    MessageDispatch.builder()
                    .channelId(s.observeChannel().id)
                    .sender("researcher-001")
                    .type(MessageType.EVENT)
                    .content("{\"tool\":\"permitted_event\"}")
                    .actorType(ActorTypeResolver.resolve("researcher-001"))
                    .build());
        });
        assertThat(result[0]).isNotNull();
        assertThat(result[0].type()).isEqualTo(MessageType.EVENT);
    }

    @Test
    void oversightChannel_rejectsEvent_serverSide() {
        SecureCodeReviewScenario s = scenario("enf-4-");
        QuarkusTransaction.requiringNew().run(s::setupChannels);

        assertThatThrownBy(() -> QuarkusTransaction.requiringNew().run(() -> {
            messageService.dispatch(                    MessageDispatch.builder()
                    .channelId(s.oversightChannel().id)
                    .sender("agent-x")
                    .type(MessageType.EVENT)
                    .content("{\"tool\":\"blocked\"}")
                    .actorType(ActorTypeResolver.resolve("agent-x"))
                    .build());
        })).isInstanceOf(MessageTypeViolationException.class);
    }

    @Test
    void oversightChannel_permitsQuery_serverSide() {
        SecureCodeReviewScenario s = scenario("enf-5-");
        QuarkusTransaction.requiringNew().run(s::setupChannels);

        DispatchResult[] result = new DispatchResult[1];
        QuarkusTransaction.requiringNew().run(() -> {
            result[0] = messageService.dispatch(                    MessageDispatch.builder()
                    .channelId(s.oversightChannel().id)
                    .sender("human-001")
                    .type(MessageType.QUERY)
                    .content("What is the current analysis status?")
                    .correlationId("corr-" + System.nanoTime())
                    .target("instance:researcher-001")
                    .actorType(ActorTypeResolver.resolve("human-001"))
                    .build());
        });
        assertThat(result[0]).isNotNull();
        assertThat(result[0].type()).isEqualTo(MessageType.QUERY);
    }

    @Test
    void oversightChannel_permitsCommand_serverSide() {
        SecureCodeReviewScenario s = scenario("enf-6-");
        QuarkusTransaction.requiringNew().run(s::setupChannels);

        DispatchResult[] result = new DispatchResult[1];
        QuarkusTransaction.requiringNew().run(() -> {
            result[0] = messageService.dispatch(                    MessageDispatch.builder()
                    .channelId(s.oversightChannel().id)
                    .sender("human-001")
                    .type(MessageType.COMMAND)
                    .content("Halt analysis immediately")
                    .correlationId("corr-" + System.nanoTime())
                    .target("instance:researcher-001")
                    .actorType(ActorTypeResolver.resolve("human-001"))
                    .build());
        });
        assertThat(result[0]).isNotNull();
        assertThat(result[0].type()).isEqualTo(MessageType.COMMAND);
    }

    @Test
    void workChannel_permitsAllNineTypes() {
        SecureCodeReviewScenario s = scenario("enf-7-");
        QuarkusTransaction.requiringNew().run(s::setupChannels);

        for (MessageType t : MessageType.values()) {
            // Reply types (RESPONSE, DONE, DECLINE, FAILURE, HANDOFF) require a prior COMMAND
            // to supply inReplyTo. Send a prereq COMMAND in a separate transaction first.
            boolean isReplyType = t == MessageType.RESPONSE || t == MessageType.DONE
                    || t == MessageType.DECLINE || t == MessageType.FAILURE
                    || t == MessageType.HANDOFF;

            Long[] prereqId = new Long[1];
            String corrId = "corr-" + t.name() + "-" + System.nanoTime();

            if (isReplyType) {
                QuarkusTransaction.requiringNew().run(() -> {
                    DispatchResult cmd = messageService.dispatch(MessageDispatch.builder()
                            .channelId(s.workChannel().id)
                            .sender("setup-agent")
                            .type(MessageType.COMMAND)
                            .content("setup for " + t.name())
                            .correlationId(corrId)
                            .actorType(ActorTypeResolver.resolve("setup-agent"))
                            .build());
                    prereqId[0] = cmd.messageId();
                });
            }

            QuarkusTransaction.requiringNew().run(() -> {
                String target = (t == MessageType.HANDOFF) ? "instance:other-001" : null;
                String content = (t == MessageType.DECLINE || t == MessageType.FAILURE)
                        ? "required non-empty content"
                        : "payload for " + t;
                // For non-reply types: use corrId when the type itself requires one (QUERY, COMMAND),
                // otherwise leave correlationId null.
                String effectiveCorrId = isReplyType ? corrId
                        : (t.requiresCorrelationId() ? corrId : null);
                messageService.dispatch(MessageDispatch.builder()
                        .channelId(s.workChannel().id)
                        .sender("agent-test")
                        .type(t)
                        .content(content)
                        .correlationId(effectiveCorrId)
                        .inReplyTo(prereqId[0])
                        .target(target)
                        .actorType(ActorTypeResolver.resolve("agent-test"))
                        .build());
            });
        }
    }

    @Test
    void violationException_messageContainsChannelNameAndType() {
        SecureCodeReviewScenario s = scenario("enf-8-");
        QuarkusTransaction.requiringNew().run(s::setupChannels);

        // STATUS is not in "EVENT" — sending it to observe channel should throw with
        // channel name and type name in the message
        assertThatThrownBy(() -> QuarkusTransaction.requiringNew().run(() -> {
            messageService.dispatch(                    MessageDispatch.builder()
                    .channelId(s.observeChannel().id)
                    .sender("agent-x")
                    .type(MessageType.STATUS)
                    .content("still working")
                    .actorType(ActorTypeResolver.resolve("agent-x"))
                    .build());
        }))
                .isInstanceOf(MessageTypeViolationException.class)
                .hasMessageContaining(s.observeChannel)
                .hasMessageContaining("STATUS");
    }
}
