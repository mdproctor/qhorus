package io.casehub.qhorus.runtime.message;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import jakarta.transaction.Status;
import jakarta.transaction.Synchronization;
import jakarta.transaction.TransactionSynchronizationRegistry;

import org.mockito.ArgumentCaptor;

import org.junit.jupiter.api.Test;

import io.casehub.qhorus.api.gateway.MessageObserver;
import io.casehub.qhorus.api.gateway.MessageReceivedEvent;
import io.casehub.qhorus.api.message.MessageType;
import io.quarkus.arc.InstanceHandle;

class MessageObserverDispatcherTest {

    private final UUID channelId = UUID.randomUUID();
    private final String channelName = "test-channel";

    private Message message(MessageType type, String content, String correlationId) {
        final Message m = new Message();
        m.channelId = channelId;
        m.sender = "agent-a";
        m.messageType = type;
        m.content = content;
        m.correlationId = correlationId;
        return m;
    }

    /** Wraps an observer in a no-op handle for tests that don't verify lifecycle. */
    private static <T> InstanceHandle<T> handle(final T instance) {
        return new InstanceHandle<>() {
            @Override public T get() { return instance; }
            @Override public void close() { /* no-op: no Arc container in plain-Java tests */ }
        };
    }

    // ── Dispatch coverage ─────────────────────────────────────────────────

    @Test
    void dispatch_commandMessage_notifiesObserver() {
        final List<MessageReceivedEvent> captured = new ArrayList<>();
        final MessageObserver observer = captured::add;

        MessageObserverDispatcher.dispatch(channelName, channelId,
                message(MessageType.COMMAND, "analyse this", "corr-1"),
                List.of(handle(observer)));

        assertEquals(1, captured.size());
        final MessageReceivedEvent e = captured.get(0);
        assertEquals(channelName, e.channelName());
        assertEquals(channelId, e.channelId());
        assertEquals(MessageType.COMMAND, e.messageType());
        assertEquals("agent-a", e.senderId());
        assertEquals("corr-1", e.correlationId());
        assertEquals("analyse this", e.content());
    }

    @Test
    void dispatch_multipleObservers_allReceiveEvent() {
        final List<MessageReceivedEvent> first = new ArrayList<>();
        final List<MessageReceivedEvent> second = new ArrayList<>();

        MessageObserverDispatcher.dispatch(channelName, channelId,
                message(MessageType.RESPONSE, "done", null),
                List.of(handle(first::add), handle(second::add)));

        assertEquals(1, first.size());
        assertEquals(1, second.size());
    }

    // ── EVENT content nulling ─────────────────────────────────────────────

    @Test
    void dispatch_eventType_contentIsNull() {
        final List<MessageReceivedEvent> captured = new ArrayList<>();

        MessageObserverDispatcher.dispatch(channelName, channelId,
                message(MessageType.EVENT, "{\"tool\":\"search\",\"duration_ms\":42}", null),
                List.of(handle(captured::add)));

        assertNull(captured.get(0).content(),
                "EVENT content must be null per PP-20260508-90428f");
    }

    @Test
    void dispatch_nonEventTypes_contentPreserved() {
        for (final MessageType type : MessageType.values()) {
            if (type == MessageType.EVENT) continue;
            final List<MessageReceivedEvent> captured = new ArrayList<>();
            MessageObserverDispatcher.dispatch(channelName, channelId,
                    message(type, "payload-" + type, null),
                    List.of(handle(captured::add)));
            assertEquals("payload-" + type, captured.get(0).content(),
                    "content must be preserved for type " + type);
        }
    }

    // ── Fault isolation ───────────────────────────────────────────────────

    @Test
    void dispatch_firstObserverThrows_secondObserverStillFires() {
        final List<MessageReceivedEvent> captured = new ArrayList<>();
        final MessageObserver boom = e -> { throw new RuntimeException("simulated failure"); };

        assertDoesNotThrow(() ->
            MessageObserverDispatcher.dispatch(channelName, channelId,
                    message(MessageType.DONE, "finished", "corr-2"),
                    List.of(handle(boom), handle(captured::add))));

        assertEquals(1, captured.size());
    }

    @Test
    void dispatch_allObserversThrow_doesNotPropagateException() {
        final MessageObserver boom1 = e -> { throw new RuntimeException("boom1"); };
        final MessageObserver boom2 = e -> { throw new RuntimeException("boom2"); };

        assertDoesNotThrow(() ->
            MessageObserverDispatcher.dispatch(channelName, channelId,
                    message(MessageType.FAILURE, "error", null),
                    List.of(handle(boom1), handle(boom2))));
    }

    // ── Handle lifecycle ──────────────────────────────────────────────────

    @Test
    void dispatch_closesHandleAfterEachObserver() {
        final List<Boolean> closed = new ArrayList<>();
        final InstanceHandle<MessageObserver> handle = new InstanceHandle<>() {
            @Override public MessageObserver get() { return e -> {}; }
            @Override public void close() { closed.add(true); }
        };

        MessageObserverDispatcher.dispatch(channelName, channelId,
                message(MessageType.COMMAND, "test", null),
                List.of(handle));

        assertEquals(1, closed.size(), "handle.close() must be called once per observer");
    }

    @Test
    void dispatch_closesHandleEvenIfObserverThrows() {
        final List<Boolean> closed = new ArrayList<>();
        final InstanceHandle<MessageObserver> handle = new InstanceHandle<>() {
            @Override public MessageObserver get() { return e -> { throw new RuntimeException("boom"); }; }
            @Override public void close() { closed.add(true); }
        };

        assertDoesNotThrow(() ->
            MessageObserverDispatcher.dispatch(channelName, channelId,
                    message(MessageType.COMMAND, "test", null),
                    List.of(handle)));

        assertEquals(1, closed.size(), "handle.close() must be called even when observer throws");
    }

    @Test
    void dispatch_closesAllHandlesEvenWhenFirstObserverThrows() {
        final List<Integer> closed = new ArrayList<>();
        final InstanceHandle<MessageObserver> h1 = new InstanceHandle<>() {
            @Override public MessageObserver get() { return e -> { throw new RuntimeException("boom"); }; }
            @Override public void close() { closed.add(1); }
        };
        final InstanceHandle<MessageObserver> h2 = new InstanceHandle<>() {
            @Override public MessageObserver get() { return e -> {}; }
            @Override public void close() { closed.add(2); }
        };

        assertDoesNotThrow(() ->
            MessageObserverDispatcher.dispatch(channelName, channelId,
                    message(MessageType.COMMAND, "test", null),
                    List.of(h1, h2)));

        assertEquals(List.of(1, 2), closed, "both handles must be closed even when first observer throws");
    }

    // ── Nullable fields ───────────────────────────────────────────────────

    @Test
    void dispatch_nullChannel_channelNameIsNull() {
        final List<MessageReceivedEvent> captured = new ArrayList<>();

        MessageObserverDispatcher.dispatch(null, channelId,
                message(MessageType.QUERY, "hello", null),
                List.of(handle(captured::add)));

        assertNull(captured.get(0).channelName());
    }

    @Test
    void dispatch_nullCorrelationId_propagatesNull() {
        final List<MessageReceivedEvent> captured = new ArrayList<>();

        MessageObserverDispatcher.dispatch(channelName, channelId,
                message(MessageType.STATUS, "working", null),
                List.of(handle(captured::add)));

        assertNull(captured.get(0).correlationId());
    }

    // ── JTA deferred dispatch ─────────────────────────────────────────────

    @Test
    void dispatch_withTsr_defersObserverCallUntilAfterCommit() {
        final List<MessageReceivedEvent> captured = new ArrayList<>();
        final MessageObserver observer = captured::add;
        final TransactionSynchronizationRegistry tsr = mock(TransactionSynchronizationRegistry.class);
        final ArgumentCaptor<Synchronization> captor = ArgumentCaptor.forClass(Synchronization.class);

        MessageObserverDispatcher.dispatch(channelName, channelId,
                message(MessageType.COMMAND, "test", null),
                List.of(handle(observer)), tsr);

        assertTrue(captured.isEmpty(), "observer must not fire before afterCompletion");
        verify(tsr).registerInterposedSynchronization(captor.capture());

        captor.getValue().afterCompletion(Status.STATUS_COMMITTED);

        assertEquals(1, captured.size(), "observer must fire after commit");
    }

    @Test
    void dispatch_withTsr_doesNotFireObserverOnRollback() {
        final List<MessageReceivedEvent> captured = new ArrayList<>();
        final MessageObserver observer = captured::add;
        final TransactionSynchronizationRegistry tsr = mock(TransactionSynchronizationRegistry.class);
        final ArgumentCaptor<Synchronization> captor = ArgumentCaptor.forClass(Synchronization.class);

        MessageObserverDispatcher.dispatch(channelName, channelId,
                message(MessageType.COMMAND, "test", null),
                List.of(handle(observer)), tsr);

        verify(tsr).registerInterposedSynchronization(captor.capture());
        captor.getValue().afterCompletion(Status.STATUS_ROLLEDBACK);

        assertTrue(captured.isEmpty(), "observer must not fire when transaction rolls back");
    }

    @Test
    void dispatch_withNullTsr_firesObserverSynchronously() {
        // null TSR = no active transaction or test context — fall back to synchronous dispatch
        final List<MessageReceivedEvent> captured = new ArrayList<>();

        MessageObserverDispatcher.dispatch(channelName, channelId,
                message(MessageType.QUERY, "hello", null),
                List.of(handle(captured::add)), null);

        assertEquals(1, captured.size(), "null TSR must dispatch synchronously");
    }

    @Test
    void dispatch_withTsr_channelFilterAppliedBeforeDefer() {
        final List<MessageReceivedEvent> captured = new ArrayList<>();
        final MessageObserver filtered = new MessageObserver() {
            @Override public void onMessage(MessageReceivedEvent e) { captured.add(e); }
            @Override public Set<String> channels() { return Set.of("other"); }
        };
        final TransactionSynchronizationRegistry tsr = mock(TransactionSynchronizationRegistry.class);

        MessageObserverDispatcher.dispatch(channelName, channelId,
                message(MessageType.QUERY, "hello", null),
                List.of(handle(filtered)), tsr);

        // Filtered out — no synchronization registered
        verify(tsr, never()).registerInterposedSynchronization(any());
    }

    // ── Per-channel filter ────────────────────────────────────────────────

    @Test
    void dispatch_observerWithMatchingChannel_receives() {
        final List<MessageReceivedEvent> captured = new ArrayList<>();
        final MessageObserver filtered = new MessageObserver() {
            @Override public void onMessage(MessageReceivedEvent e) { captured.add(e); }
            @Override public Set<String> channels() { return Set.of(channelName); }
        };

        MessageObserverDispatcher.dispatch(channelName, channelId,
                message(MessageType.QUERY, "hello", null),
                List.of(handle(filtered)));

        assertEquals(1, captured.size());
    }

    @Test
    void dispatch_observerWithNonMatchingChannel_isSkipped() {
        final List<MessageReceivedEvent> captured = new ArrayList<>();
        final MessageObserver filtered = new MessageObserver() {
            @Override public void onMessage(MessageReceivedEvent e) { captured.add(e); }
            @Override public Set<String> channels() { return Set.of("other-channel"); }
        };

        MessageObserverDispatcher.dispatch(channelName, channelId,
                message(MessageType.QUERY, "hello", null),
                List.of(handle(filtered)));

        assertTrue(captured.isEmpty(), "observer not subscribed to this channel must be skipped");
    }

    @Test
    void dispatch_observerWithEmptyChannels_receivesAll() {
        // default channels() = Set.of() → receives messages from every channel
        final List<MessageReceivedEvent> captured = new ArrayList<>();
        final MessageObserver globalObserver = captured::add; // lambda uses default channels()

        MessageObserverDispatcher.dispatch(channelName, channelId,
                message(MessageType.COMMAND, "analyse", null),
                List.of(handle(globalObserver)));

        assertEquals(1, captured.size());
    }

    @Test
    void dispatch_observerWithMultipleChannels_receivesWhenMatched() {
        final List<MessageReceivedEvent> captured = new ArrayList<>();
        final MessageObserver filtered = new MessageObserver() {
            @Override public void onMessage(MessageReceivedEvent e) { captured.add(e); }
            @Override public Set<String> channels() { return Set.of("other-channel", channelName, "yet-another"); }
        };

        MessageObserverDispatcher.dispatch(channelName, channelId,
                message(MessageType.STATUS, "working", null),
                List.of(handle(filtered)));

        assertEquals(1, captured.size());
    }

    @Test
    void dispatch_noObservers_noException() {
        assertDoesNotThrow(() ->
            MessageObserverDispatcher.dispatch(channelName, channelId,
                    message(MessageType.HANDOFF, "over to you", "corr-3"),
                    List.of()));
    }

    // ── MessageReceivedEvent compact constructor ───────────────────────────

    @Test
    void messageReceivedEvent_eventWithContent_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class, () ->
            new io.casehub.qhorus.api.gateway.MessageReceivedEvent(
                channelName, channelId, MessageType.EVENT, "agent-a", null, "non-null content"));
    }

    @Test
    void messageReceivedEvent_eventWithNullContent_isValid() {
        assertDoesNotThrow(() ->
            new io.casehub.qhorus.api.gateway.MessageReceivedEvent(
                channelName, channelId, MessageType.EVENT, "agent-a", null, null));
    }
}
