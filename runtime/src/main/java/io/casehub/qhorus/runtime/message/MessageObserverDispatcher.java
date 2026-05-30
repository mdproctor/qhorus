package io.casehub.qhorus.runtime.message;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.enterprise.inject.Instance;
import jakarta.transaction.Status;
import jakarta.transaction.Synchronization;
import jakarta.transaction.TransactionSynchronizationRegistry;

import org.jboss.logging.Logger;

import io.casehub.qhorus.api.gateway.MessageObserver;
import io.casehub.qhorus.api.gateway.MessageReceivedEvent;
import io.casehub.qhorus.api.message.MessageType;

/**
 * Shared dispatch logic for blocking and reactive message services.
 * Each observer is called independently — a failure in one does not affect others.
 *
 * <p><strong>Lifecycle:</strong> each {@link Instance.Handle} is closed in a
 * {@code finally} block after {@code onMessage()} returns or throws. This
 * correctly destroys {@code @Dependent}-scoped observers and is a no-op for
 * normal-scoped ones ({@code @ApplicationScoped}, {@code @RequestScoped}, etc.).
 *
 * <p><strong>Transaction timing:</strong> this method is called inside the
 * {@code MessageService.dispatch()} transaction (blocking) or {@code ReactiveMessageService.dispatch()}
 * transaction (reactive), before the enclosing transaction commits. Observers that
 * call {@code fireAsync()} (like {@link io.casehub.qhorus.runtime.gateway.InProcessMessageBus})
 * run in a separate thread that may start before the enclosing transaction commits.
 * Therefore: <em>observer implementations must not query qhorus message state</em>
 * in response to this event. The {@link MessageReceivedEvent} payload is intentionally
 * self-contained to make a DB read unnecessary. JTA after-commit dispatch tracked in qhorus#166.
 */
final class MessageObserverDispatcher {

    private static final Logger LOG = Logger.getLogger(MessageObserverDispatcher.class);

    private MessageObserverDispatcher() {}

    /** Synchronous dispatch — used by tests and contexts with no active transaction. */
    static void dispatch(final String channelName, final UUID channelId,
            final Message message,
            final Iterable<? extends Instance.Handle<MessageObserver>> handles) {
        dispatch(channelName, channelId, message, handles, null);
    }

    /**
     * Dispatches to all registered {@link MessageObserver} implementations.
     *
     * <p>When {@code tsr} is non-null, observer calls are deferred to the
     * {@code afterCompletion(STATUS_COMMITTED)} JTA callback so that observers
     * see a fully committed message. Rolled-back transactions skip all observer
     * calls. Refs #166.
     *
     * <p>When {@code tsr} is null (unit-test context or no active transaction),
     * observers are called synchronously in the current thread (original behaviour).
     */
    static void dispatch(final String channelName, final UUID channelId,
            final Message message,
            final Iterable<? extends Instance.Handle<MessageObserver>> handles,
            final TransactionSynchronizationRegistry tsr) {
        final String content = message.messageType == MessageType.EVENT
                ? null : message.content;
        final MessageReceivedEvent event = new MessageReceivedEvent(
                channelName, channelId,
                message.messageType, message.sender,
                message.correlationId, content);

        // Apply channel filter and collect handles that will receive the event.
        final List<Instance.Handle<MessageObserver>> active = new ArrayList<>();
        for (final Instance.Handle<MessageObserver> handle : handles) {
            MessageObserver observer = null;
            try {
                observer = handle.get();
                final java.util.Set<String> filter = observer.channels();
                if (!filter.isEmpty() && !filter.contains(channelName)) {
                    handle.close();
                    continue;
                }
                active.add(handle);
            } catch (Exception e) {
                LOG.warnf("MessageObserver handle.get() failed for channel '%s': %s",
                        channelName, e.getMessage());
                handle.close();
            }
        }

        if (active.isEmpty()) {
            return;
        }

        if (tsr == null || tsr.getTransactionStatus() != Status.STATUS_ACTIVE) {
            // No active transaction, test context, or TX already marked for rollback —
            // dispatch synchronously. Rollback-only transactions will not commit, so
            // deferral would never fire; synchronous dispatch is best-effort.
            dispatchToHandles(channelName, message.messageType, event, active);
            return;
        }

        // Defer dispatch to post-commit to guarantee message visibility in the DB. Refs #166.
        tsr.registerInterposedSynchronization(new Synchronization() {
            @Override
            public void beforeCompletion() {}

            @Override
            public void afterCompletion(final int status) {
                if (status == Status.STATUS_COMMITTED) {
                    dispatchToHandles(channelName, message.messageType, event, active);
                } else {
                    active.forEach(Instance.Handle::close);
                }
            }
        });
    }

    private static void dispatchToHandles(final String channelName, final MessageType messageType,
            final MessageReceivedEvent event,
            final List<Instance.Handle<MessageObserver>> handles) {
        for (final Instance.Handle<MessageObserver> handle : handles) {
            try {
                final MessageObserver observer = handle.get();
                try {
                    observer.onMessage(event);
                } catch (Exception e) {
                    LOG.warnf("MessageObserver %s failed for channel '%s' type %s: %s",
                            observerName(observer), channelName, messageType, e.getMessage());
                }
            } catch (Exception e) {
                LOG.warnf("MessageObserver handle.get() failed during post-commit dispatch for channel '%s': %s",
                        channelName, e.getMessage());
            } finally {
                handle.close();
            }
        }
    }

    /** Returns a readable class name that works for both CDI proxies and lambda observers. */
    private static String observerName(final MessageObserver observer) {
        final Class<?> cls = observer.getClass();
        final Class<?> sup = cls.getSuperclass();
        return (sup != null && sup != Object.class) ? sup.getSimpleName() : cls.getSimpleName();
    }
}
