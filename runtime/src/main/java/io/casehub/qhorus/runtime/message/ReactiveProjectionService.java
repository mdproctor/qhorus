package io.casehub.qhorus.runtime.message;

import java.util.Objects;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.casehub.qhorus.api.spi.ChannelProjection;
import io.casehub.qhorus.api.spi.ProjectionResult;
import io.casehub.qhorus.runtime.QhorusEntityMapper;
import io.casehub.qhorus.api.store.ReactiveMessageStore;
import io.casehub.qhorus.api.store.query.MessageQuery;
import io.quarkus.arc.properties.IfBuildProperty;
import io.smallrye.mutiny.Uni;

/**
 * Reactive mirror of {@link ProjectionService}.
 *
 * <p>Uses {@link ReactiveMessageStore#stream(MessageQuery)} for message iteration.
 * The current JPA implementation materialises the full result list (Quarkus 3.32
 * Hibernate Reactive Panache does not expose cursor streaming). The fold operator
 * is correct for when cursor streaming becomes available — only the store changes.
 *
 * <p>The fold uses Mutiny's {@code collect().in()} operator with a private mutable
 * accumulator ({@link FoldAcc}). {@code collect().in()} takes a {@code BiConsumer}
 * (mutation, void return), so the accumulator must be mutable — it cannot use the
 * immutable {@code ChannelProjection.apply()} directly without a wrapper.
 *
 * <p>See {@link ProjectionService} for full behavioural documentation (LAST_WRITE
 * channels, unknown channelId, threading contract, etc.).
 */
@IfBuildProperty(name = "casehub.qhorus.reactive.enabled", stringValue = "true")
@ApplicationScoped
public class ReactiveProjectionService {

    @Inject
    ReactiveMessageStore reactiveMessageStore;

    @Inject
    QhorusEntityMapper mapper;

    ReactiveProjectionService() {}

    /** For unit testing without CDI. */
    ReactiveProjectionService(final ReactiveMessageStore store, final QhorusEntityMapper mapper) {
        this.reactiveMessageStore = store;
        this.mapper = mapper;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /** Project all messages in {@code channelId} from the beginning. */
    public <S> Uni<ProjectionResult<S>> project(final UUID channelId,
                                                  final ChannelProjection<S> projection) {
        Objects.requireNonNull(channelId, "channelId");
        Objects.requireNonNull(projection, "projection");
        return reactiveFold(
                MessageQuery.builder().channelId(channelId).build(),
                projection.identity(), null, projection);
    }

    /** Scoped projection. Scope validation rules match {@link ProjectionService}. */
    public <S> Uni<ProjectionResult<S>> project(final UUID channelId,
                                                  final MessageQuery scope,
                                                  final ChannelProjection<S> projection) {
        Objects.requireNonNull(channelId, "channelId");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(projection, "projection");
        validateScope(channelId, scope);
        final MessageQuery query = scope.toBuilder().channelId(channelId).build();
        return reactiveFold(query, projection.identity(), null, projection);
    }

    /** Incremental — resumes from {@code previous.lastMessageId()}. */
    public <S> Uni<ProjectionResult<S>> project(final UUID channelId,
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
        return reactiveFold(query, initialState, previous.lastMessageId(), projection);
    }

    /** Scoped incremental. */
    public <S> Uni<ProjectionResult<S>> project(final UUID channelId,
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
        return reactiveFold(query, initialState, previous.lastMessageId(), projection);
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    /**
     * Fold using {@code collect().in()} with a mutable {@link FoldAcc}.
     *
     * <p>{@code collect().in()} requires a mutable container ({@code BiConsumer} — void return).
     * {@code ChannelProjection.apply()} is a pure {@code BiFunction} returning a new state,
     * so it cannot be passed directly. {@link FoldAcc} provides the mutable wrapper.
     */
    private <S> Uni<ProjectionResult<S>> reactiveFold(final MessageQuery query,
                                                        final S initialState,
                                                        final Long cursorIn,
                                                        final ChannelProjection<S> projection) {
        return reactiveMessageStore.stream(query)
                .collect().in(
                        () -> new FoldAcc<>(initialState, cursorIn),
                        (acc, msg) -> {
                            acc.state = projection.apply(acc.state, mapper.toMessageView(msg));
                            acc.lastId = msg.id();
                        })
                .map(acc -> new ProjectionResult<>(acc.state, acc.lastId));
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

    /** Mutable accumulator — private, never exposed outside this class. */
    private static final class FoldAcc<S> {
        S state;
        Long lastId;

        FoldAcc(final S state, final Long lastId) {
            this.state = state;
            this.lastId = lastId;
        }
    }
}
