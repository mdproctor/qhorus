package io.casehub.qhorus.runtime.message;

import java.util.Objects;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.casehub.qhorus.api.spi.ChannelProjection;
import io.casehub.qhorus.api.spi.ProjectionResult;
import io.casehub.qhorus.runtime.QhorusEntityMapper;
import io.casehub.qhorus.runtime.store.MessageStore;
import io.casehub.qhorus.runtime.store.query.MessageQuery;

/**
 * Folds a channel's message history through a {@link ChannelProjection} and returns
 * a {@link ProjectionResult} containing the materialised state and a cursor.
 *
 * <p>All reads go through the {@link MessageStore} seam — never direct Panache access.
 * The fold is always ascending by insertion order ({@code id ASC}); descending queries
 * are rejected at the scope-validation gate.
 *
 * <p><strong>LAST_WRITE channels:</strong> on a LAST_WRITE channel,
 * {@code MessageStore.scan()} returns at most one message per sender (the current
 * value — history is overwritten in place). A projection over such a channel folds
 * the current snapshot only, not the full history. Useful for "who has checked in?"
 * projections; produces wrong results for time-series or tally projections.
 * Consumers must choose channel semantics that match their projection's assumptions.
 *
 * <p><strong>Unknown channelId:</strong> a {@code project()} call with a non-existent
 * channelId returns {@code new ProjectionResult<>(projection.identity(), null)} —
 * indistinguishable from an empty channel. To distinguish, call
 * {@code ChannelStore.find(channelId)} separately.
 */
@ApplicationScoped
public class ProjectionService {

    @Inject
    MessageStore messageStore;

    @Inject
    QhorusEntityMapper mapper;

    ProjectionService() {}

    /** For unit testing without CDI. */
    ProjectionService(final MessageStore messageStore, final QhorusEntityMapper mapper) {
        this.messageStore = messageStore;
        this.mapper = mapper;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Project all messages in {@code channelId} from the beginning.
     *
     * @throws NullPointerException if {@code channelId} or {@code projection} is null
     */
    public <S> ProjectionResult<S> project(final UUID channelId,
                                            final ChannelProjection<S> projection) {
        Objects.requireNonNull(channelId, "channelId");
        Objects.requireNonNull(projection, "projection");
        return fold(MessageQuery.builder().channelId(channelId).build(),
                    projection.identity(), null, projection);
    }

    /**
     * Project messages in {@code channelId} matching {@code scope}.
     *
     * <p>Scope validation rules:
     * <ul>
     *   <li>If {@code scope.channelId()} is non-null and differs from {@code channelId},
     *       {@link IllegalArgumentException} is thrown — remove it from the scope builder.</li>
     *   <li>{@code scope.descending(true)} throws — projections always fold ascending
     *       (insertion order). Strip the flag before passing the scope.</li>
     * </ul>
     *
     * @throws NullPointerException     if any argument is null
     * @throws IllegalArgumentException if scope violates the rules above
     */
    public <S> ProjectionResult<S> project(final UUID channelId,
                                            final MessageQuery scope,
                                            final ChannelProjection<S> projection) {
        Objects.requireNonNull(channelId, "channelId");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(projection, "projection");
        validateScope(channelId, scope);
        final MessageQuery query = scope.toBuilder().channelId(channelId).build();
        return fold(query, projection.identity(), null, projection);
    }

    /**
     * Resume a fold from a previous result. Only messages with
     * {@code id > previous.lastMessageId()} are folded.
     *
     * <p>If {@code previous.isEmpty()} (channel was empty at last projection),
     * a full scan is performed starting from {@code identity()} — {@code previous.state()}
     * is ignored regardless of its value. {@code lastMessageId == null} unambiguously
     * means "fold from scratch."
     *
     * @throws NullPointerException if any argument is null
     */
    public <S> ProjectionResult<S> project(final UUID channelId,
                                            final ProjectionResult<S> previous,
                                            final ChannelProjection<S> projection) {
        Objects.requireNonNull(channelId, "channelId");
        Objects.requireNonNull(previous, "previous");
        Objects.requireNonNull(projection, "projection");
        final S initialState = previous.isEmpty() ? projection.identity() : previous.state();
        final MessageQuery query = MessageQuery.builder()
                .channelId(channelId)
                .afterId(previous.lastMessageId())
                .build();
        return fold(query, initialState, previous.lastMessageId(), projection);
    }

    /**
     * Scoped incremental — scope rules same as the scoped-full overload.
     *
     * @throws NullPointerException     if any argument is null
     * @throws IllegalArgumentException if scope violates the validation rules
     */
    public <S> ProjectionResult<S> project(final UUID channelId,
                                            final ProjectionResult<S> previous,
                                            final MessageQuery scope,
                                            final ChannelProjection<S> projection) {
        Objects.requireNonNull(channelId, "channelId");
        Objects.requireNonNull(previous, "previous");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(projection, "projection");
        validateScope(channelId, scope);
        final S initialState = previous.isEmpty() ? projection.identity() : previous.state();
        final MessageQuery query = scope.toBuilder()
                .channelId(channelId)
                .afterId(previous.lastMessageId())
                .build();
        return fold(query, initialState, previous.lastMessageId(), projection);
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private <S> ProjectionResult<S> fold(final MessageQuery query,
                                          final S initialState,
                                          final Long cursorIn,
                                          final ChannelProjection<S> projection) {
        final var messages = messageStore.scan(query);
        var state = initialState;
        var lastId = cursorIn;
        for (final var msg : messages) {
            state = projection.apply(state, mapper.toMessageView(msg));
            lastId = msg.id;
        }
        return new ProjectionResult<>(state, lastId);
    }

    private static void validateScope(final UUID channelId, final MessageQuery scope) {
        if (scope.channelId() != null && !scope.channelId().equals(channelId)) {
            throw new IllegalArgumentException(
                    "scope.channelId() conflicts with channelId parameter — remove it from scope");
        }
        if (scope.descending()) {
            throw new IllegalArgumentException(
                    "scope.descending(true) breaks fold order — projections always fold ascending");
        }
    }
}
