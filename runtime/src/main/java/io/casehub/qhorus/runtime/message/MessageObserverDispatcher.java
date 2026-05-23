package io.casehub.qhorus.runtime.message;

import java.util.UUID;

import jakarta.enterprise.inject.Instance;

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

    static void dispatch(final String channelName, final UUID channelId,
            final Message message,
            final Iterable<? extends Instance.Handle<MessageObserver>> handles) {
        final String content = message.messageType == MessageType.EVENT
                ? null : message.content;
        final MessageReceivedEvent event = new MessageReceivedEvent(
                channelName, channelId,
                message.messageType, message.sender,
                message.correlationId, content);
        for (final Instance.Handle<MessageObserver> handle : handles) {
            try {
                final MessageObserver observer = handle.get();
                try {
                    observer.onMessage(event);
                } catch (Exception e) {
                    LOG.warnf("MessageObserver %s failed for channel '%s' type %s: %s",
                            observerName(observer), channelName, message.messageType, e.getMessage());
                }
            } catch (Exception e) {
                LOG.warnf("MessageObserver handle.get() failed for channel '%s': %s",
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
