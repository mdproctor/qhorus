package io.casehub.qhorus.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.api.message.MessageView;
import io.casehub.qhorus.api.spi.ChannelProjection;
import io.casehub.qhorus.api.spi.ProjectionResult;
import io.casehub.qhorus.api.channel.Channel;
import io.casehub.qhorus.api.message.Message;
import io.casehub.qhorus.runtime.message.ProjectionService;
import io.casehub.qhorus.api.store.MessageStore;
import io.casehub.qhorus.api.store.query.MessageQuery;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;

/**
 * Integration tests for {@link ProjectionService} against H2/JPA.
 *
 * <p>Uses {@code JpaMessageStore} — {@code InMemoryMessageStore} is in the
 * {@code casehub-qhorus-testing} consumer module and is NOT on this classpath.
 * Messages are written via {@code MessageStore.put()} directly to keep tests
 * focused on projection behaviour, not dispatch enforcement.
 */
@QuarkusTest
class ProjectionServiceIT {

    @Inject
    ProjectionService projectionService;

    @Inject
    MessageStore messageStore;

    @Inject
    io.casehub.qhorus.api.store.ChannelStore channelStore;

    // ── Happy path ────────────────────────────────────────────────────────────

    @Test
    @TestTransaction
    void project_emptyChannel_returnsIdentityAndNullCursor() {
        final var channelId = newChannel();

        final ProjectionResult<VoteState> result =
                projectionService.project(channelId, new VoteTallyProjection());

        assertThat(result.state().approvals()).isZero();
        assertThat(result.state().declines()).isZero();
        assertThat(result.isEmpty()).isTrue();
        assertThat(result.lastMessageId()).isNull();
    }

    @Test
    @TestTransaction
    void project_singleMessage_returnsFoldedStateAndCursor() {
        final var           channelId = newChannel();
        final Message m         = put(channelId, "alice", MessageType.COMMAND);

        final ProjectionResult<VoteState> result =
                projectionService.project(channelId, new VoteTallyProjection());

        assertThat(result.state().approvals()).isEqualTo(1);
        assertThat(result.lastMessageId()).isEqualTo(m.id());
    }

    @Test
    @TestTransaction
    void project_multipleMessages_accumulatesAll() {
        final var channelId = newChannel();
        put(channelId, "alice", MessageType.COMMAND);
        put(channelId, "bob",   MessageType.COMMAND);
        final Message last = put(channelId, "carol", MessageType.DECLINE);

        final ProjectionResult<VoteState> result =
                projectionService.project(channelId, new VoteTallyProjection());

        assertThat(result.state().approvals()).isEqualTo(2);
        assertThat(result.state().declines()).isEqualTo(1);
        assertThat(result.lastMessageId()).isEqualTo(last.id());
    }

    // ── Unknown channel ───────────────────────────────────────────────────────

    @Test
    @TestTransaction
    void project_unknownChannelId_returnsIdentityAndNullCursor() {
        final ProjectionResult<VoteState> result =
                projectionService.project(UUID.randomUUID(), new VoteTallyProjection());

        assertThat(result.state().approvals()).isZero();
        assertThat(result.isEmpty()).isTrue();
    }

    // ── Scoped projection ─────────────────────────────────────────────────────

    @Test
    @TestTransaction
    void project_scoped_excludesFilteredTypes() {
        final var channelId = newChannel();
        put(channelId, "alice", MessageType.COMMAND);
        put(channelId, "bob",   MessageType.EVENT);   // should be excluded
        put(channelId, "carol", MessageType.DECLINE);

        final var scope = MessageQuery.builder()
                .excludeTypes(List.of(MessageType.EVENT))
                .build();

        final ProjectionResult<VoteState> result =
                projectionService.project(channelId, scope, new VoteTallyProjection());

        assertThat(result.state().approvals()).isEqualTo(1);
        assertThat(result.state().declines()).isEqualTo(1);
    }

    // ── Incremental projection ────────────────────────────────────────────────

    @Test
    @TestTransaction
    void project_incremental_onlyFoldsNewMessages() {
        final var channelId = newChannel();
        put(channelId, "alice", MessageType.COMMAND);
        put(channelId, "bob",   MessageType.COMMAND);

        final ProjectionResult<VoteState> first =
                projectionService.project(channelId, new VoteTallyProjection());
        assertThat(first.state().approvals()).isEqualTo(2);

        // New message arrives
        put(channelId, "carol", MessageType.DECLINE);

        final ProjectionResult<VoteState> second =
                projectionService.project(channelId, first, new VoteTallyProjection());

        assertThat(second.state().approvals()).isEqualTo(2);
        assertThat(second.state().declines()).isEqualTo(1);
    }

    @Test
    @TestTransaction
    void project_incremental_emptyPrevious_performsFullScan() {
        final var channelId = newChannel();
        // Channel is initially empty — get a null-cursor result
        final ProjectionResult<VoteState> empty =
                projectionService.project(channelId, new VoteTallyProjection());
        assertThat(empty.isEmpty()).isTrue();

        // Messages arrive
        put(channelId, "alice", MessageType.COMMAND);
        put(channelId, "bob",   MessageType.DECLINE);

        // Incremental from empty previous should full-scan from identity()
        final ProjectionResult<VoteState> resumed =
                projectionService.project(channelId, empty, new VoteTallyProjection());

        assertThat(resumed.state().approvals()).isEqualTo(1);
        assertThat(resumed.state().declines()).isEqualTo(1);
    }

    @Test
    @TestTransaction
    void project_scoped_incremental_combinesFilterAndCursor() {
        final var channelId = newChannel();
        put(channelId, "alice", MessageType.COMMAND);

        final ProjectionResult<VoteState> first =
                projectionService.project(channelId, new VoteTallyProjection());

        put(channelId, "bob",  MessageType.EVENT);   // excluded by scope
        put(channelId, "carol", MessageType.DECLINE);

        final var scope = MessageQuery.builder()
                .excludeTypes(List.of(MessageType.EVENT))
                .build();

        final ProjectionResult<VoteState> second =
                projectionService.project(channelId, first, scope, new VoteTallyProjection());

        // Only DECLINE folded — EVENT excluded, COMMAND already in cursor
        assertThat(second.state().approvals()).isEqualTo(1);  // from first
        assertThat(second.state().declines()).isEqualTo(1);   // new
    }

    // ── Validation ────────────────────────────────────────────────────────────

    @Test
    void project_nullChannelId_throwsNPE() {
        assertThatThrownBy(() -> projectionService.project(null, new VoteTallyProjection()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("channelId");
    }

    @Test
    void project_nullProjection_throwsNPE() {
        assertThatThrownBy(() -> projectionService.project(UUID.randomUUID(), (ChannelProjection<Object>) null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("projection");
    }

    @Test
    void project_scopeConflictingChannelId_throwsIAE() {
        // validateScope() fires before any DB access — no channel or @TestTransaction needed
        final var channelId = UUID.randomUUID();
        final var conflictScope = MessageQuery.builder()
                .channelId(UUID.randomUUID())
                .build();

        assertThatThrownBy(() ->
                projectionService.project(channelId, conflictScope, new VoteTallyProjection()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scope.channelId()");
    }

    @Test
    void project_descendingScope_throwsIAE() {
        // validateScope() fires before any DB access — no channel or @TestTransaction needed
        final var channelId = UUID.randomUUID();
        final var badScope = MessageQuery.builder().descending(true).build();

        assertThatThrownBy(() ->
                projectionService.project(channelId, badScope, new VoteTallyProjection()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("descending");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private UUID newChannel() {
        Channel ch = channelStore.put(Channel.builder("proj-test-" + UUID.randomUUID())
                .semantic(ChannelSemantic.APPEND).build());
        return ch.id();
    }

    private Message put(final UUID channelId, final String sender, final MessageType type) {
        return messageStore.put(Message.builder()
                .channelId(channelId).sender(sender).messageType(type)
                .actorType(ActorType.AGENT).content(sender + " voted").build());
    }

    // ── Test projection implementation ────────────────────────────────────────

    record VoteState(int approvals, int declines) {}

    static final class VoteTallyProjection implements ChannelProjection<VoteState> {
        @Override
        public VoteState identity() {
            return new VoteState(0, 0);
        }

        @Override
        public VoteState apply(final VoteState state, final MessageView message) {
            return switch (message.type()) {
                case COMMAND -> new VoteState(state.approvals() + 1, state.declines());
                case DECLINE -> new VoteState(state.approvals(), state.declines() + 1);
                default -> state;
            };
        }
    }
}
