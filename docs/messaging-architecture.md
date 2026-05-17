# Qhorus Messaging Architecture

Qhorus is a governance mesh for multi-agent AI. Agents, workers, and LLMs can
run anywhere — same JVM, same machine, local network, internet. The messaging
architecture has to work across all of those topologies without the application
code knowing which one it's in.

This document describes how qhorus handles that.

---

## Channels: The Communication Primitive

Every conversation in qhorus happens on a **channel** — a named, typed, durable
message surface. Channels are not transient: they persist across reconnections
and carry a full normative history (the ledger). Any participant can join or
observe a channel by registering a backend.

Channels have declared update semantics: APPEND, COLLECT, BARRIER, EPHEMERAL,
LAST_WRITE. These affect how messages accumulate, not how they're delivered.

Messages on a channel are typed speech acts — nine types drawn from speech act
theory: QUERY, COMMAND, RESPONSE, STATUS, DECLINE, HANDOFF, DONE, FAILURE,
EVENT. Together they form a complete vocabulary for agent obligations.

---

## The Mesh: Participants Anywhere

The topology diagram below shows the problem qhorus solves. Every participant
uses the same channel API regardless of where they live.

<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 760 420" font-family="Inter, ui-sans-serif, system-ui, sans-serif">
  <defs>
    <marker id="t1-arr" markerWidth="8" markerHeight="6" refX="7" refY="3" orient="auto">
      <path d="M0,0 L0,6 L8,3 z" fill="#64748B"/>
    </marker>
    <marker id="t1-arr-purple" markerWidth="8" markerHeight="6" refX="7" refY="3" orient="auto">
      <path d="M0,0 L0,6 L8,3 z" fill="#7C3AED"/>
    </marker>
    <marker id="t1-arr-green" markerWidth="8" markerHeight="6" refX="7" refY="3" orient="auto">
      <path d="M0,0 L0,6 L8,3 z" fill="#16A34A"/>
    </marker>
    <marker id="t1-arr-orange" markerWidth="8" markerHeight="6" refX="7" refY="3" orient="auto">
      <path d="M0,0 L0,6 L8,3 z" fill="#EA580C"/>
    </marker>
    <marker id="t1-arr-teal" markerWidth="8" markerHeight="6" refX="7" refY="3" orient="auto">
      <path d="M0,0 L0,6 L8,3 z" fill="#0D9488"/>
    </marker>
    <marker id="t1-arr-pink" markerWidth="8" markerHeight="6" refX="7" refY="3" orient="auto">
      <path d="M0,0 L0,6 L8,3 z" fill="#DB2777"/>
    </marker>
  </defs>

  <!-- Background -->
  <rect width="760" height="420" fill="#F8FAFC" rx="8"/>

  <!-- Zone: WAN -->
  <ellipse cx="380" cy="210" rx="355" ry="195" fill="#FEF9C3" stroke="#FDE047" stroke-width="1.5" stroke-dasharray="6,4" opacity="0.7"/>

  <!-- Zone: LAN -->
  <ellipse cx="380" cy="210" rx="265" ry="145" fill="#DCFCE7" stroke="#4ADE80" stroke-width="1.5" stroke-dasharray="6,4" opacity="0.6"/>

  <!-- Zone: Machine -->
  <ellipse cx="380" cy="210" rx="178" ry="98" fill="#DBEAFE" stroke="#60A5FA" stroke-width="1.5" stroke-dasharray="6,4" opacity="0.6"/>

  <!-- Zone: JVM -->
  <ellipse cx="380" cy="210" rx="90" ry="52" fill="#EDE9FE" stroke="#A78BFA" stroke-width="1.5" opacity="0.7"/>

  <!-- Zone labels -->
  <text x="726" y="50" fill="#713F12" font-size="11" font-weight="600">WAN</text>
  <text x="638" y="90" fill="#14532D" font-size="11" font-weight="600">LAN</text>
  <text x="549" y="130" fill="#1E3A8A" font-size="11" font-weight="600">Machine</text>
  <text x="400" y="167" fill="#4C1D95" font-size="11" font-weight="600" text-anchor="middle">JVM</text>

  <!-- Qhorus center -->
  <rect x="322" y="188" width="116" height="44" rx="8" fill="#2563EB" stroke="#1D4ED8" stroke-width="2"/>
  <text x="380" y="206" fill="white" font-size="13" font-weight="700" text-anchor="middle">qhorus</text>
  <text x="380" y="222" fill="#BFDBFE" font-size="9" text-anchor="middle">channel mesh</text>

  <!-- Agent A — same JVM -->
  <rect x="200" y="190" width="106" height="40" rx="6" fill="#6D28D9" stroke="#5B21B6" stroke-width="1.5"/>
  <text x="253" y="207" fill="white" font-size="11" font-weight="600" text-anchor="middle">Harness</text>
  <text x="253" y="221" fill="#DDD6FE" font-size="9" text-anchor="middle">same JVM</text>
  <!-- Arrow -->
  <line x1="306" y1="210" x2="322" y2="210" stroke="#7C3AED" stroke-width="2" marker-end="url(#t1-arr-purple)"/>
  <rect x="302" y="196" width="16" height="12" rx="2" fill="#EDE9FE"/>
  <text x="310" y="206" fill="#5B21B6" font-size="8" text-anchor="middle">CDI</text>

  <!-- Agent B — same machine different process -->
  <rect x="430" y="120" width="126" height="40" rx="6" fill="#15803D" stroke="#166534" stroke-width="1.5"/>
  <text x="493" y="137" fill="white" font-size="11" font-weight="600" text-anchor="middle">Agent / Worker</text>
  <text x="493" y="151" fill="#BBF7D0" font-size="9" text-anchor="middle">same machine</text>
  <!-- Arrow -->
  <line x1="438" y1="145" x2="415" y2="200" stroke="#16A34A" stroke-width="2" marker-end="url(#t1-arr-green)"/>
  <rect x="411" y="158" width="30" height="12" rx="2" fill="#DCFCE7"/>
  <text x="426" y="168" fill="#14532D" font-size="8" text-anchor="middle">IPC</text>

  <!-- Claudony terminal — LAN -->
  <rect x="530" y="220" width="140" height="40" rx="6" fill="#EA580C" stroke="#C2410C" stroke-width="1.5"/>
  <text x="600" y="237" fill="white" font-size="11" font-weight="600" text-anchor="middle">Claudony Terminal</text>
  <text x="600" y="251" fill="#FED7AA" font-size="9" text-anchor="middle">LAN</text>
  <!-- Arrow -->
  <line x1="530" y1="238" x2="438" y2="222" stroke="#EA580C" stroke-width="2" marker-end="url(#t1-arr-orange)"/>
  <rect x="459" y="222" width="46" height="12" rx="2" fill="#FFF7ED"/>
  <text x="482" y="232" fill="#9A3412" font-size="8" text-anchor="middle">TCP / WS</text>

  <!-- A2A external — WAN -->
  <rect x="88" y="90" width="140" height="40" rx="6" fill="#0D9488" stroke="#0F766E" stroke-width="1.5"/>
  <text x="158" y="107" fill="white" font-size="11" font-weight="600" text-anchor="middle">A2A Agent</text>
  <text x="158" y="121" fill="#CCFBF1" font-size="9" text-anchor="middle">internet / WAN</text>
  <!-- Arrow -->
  <line x1="228" y1="113" x2="348" y2="195" stroke="#0D9488" stroke-width="2" marker-end="url(#t1-arr-teal)"/>
  <rect x="256" y="137" width="58" height="12" rx="2" fill="#F0FDFA"/>
  <text x="285" y="147" fill="#0F766E" font-size="8" text-anchor="middle">A2A / HTTP</text>

  <!-- Human via backend — WAN -->
  <rect x="530" y="330" width="140" height="40" rx="6" fill="#DB2777" stroke="#BE185D" stroke-width="1.5"/>
  <text x="600" y="347" fill="white" font-size="11" font-weight="600" text-anchor="middle">Human (PI)</text>
  <text x="600" y="361" fill="#FBCFE8" font-size="9" text-anchor="middle">WhatsApp / form / WAN</text>
  <!-- Arrow -->
  <line x1="540" y1="330" x2="430" y2="232" stroke="#DB2777" stroke-width="2" marker-end="url(#t1-arr-pink)"/>
  <rect x="457" y="274" width="76" height="12" rx="2" fill="#FDF2F8"/>
  <text x="495" y="284" fill="#9D174D" font-size="8" text-anchor="middle">HumanBackend</text>

  <!-- LLM worker — LAN -->
  <rect x="80" y="280" width="140" height="40" rx="6" fill="#0369A1" stroke="#075985" stroke-width="1.5"/>
  <text x="150" y="297" fill="white" font-size="11" font-weight="600" text-anchor="middle">LLM Worker</text>
  <text x="150" y="311" fill="#BAE6FD" font-size="9" text-anchor="middle">LAN / cloud</text>
  <!-- Arrow -->
  <line x1="220" y1="300" x2="340" y2="225" stroke="#0369A1" stroke-width="2" marker-end="url(#t1-arr)"/>
  <rect x="245" y="254" width="56" height="12" rx="2" fill="#F0F9FF"/>
  <text x="273" y="264" fill="#075985" font-size="8" text-anchor="middle">MCP / REST</text>
</svg>

The API is identical for all participants. A Claudony terminal on a remote machine
calls the same MCP tools as an embedded harness running in the same JVM. The mesh
abstracts topology.

---

## The Two Notification Paths

When `MessageService.send()` persists a message, two independent notification
mechanisms fire. They serve different purposes and work at different scopes.

<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 720 380" font-family="Inter, ui-sans-serif, system-ui, sans-serif">
  <defs>
    <marker id="p2-arr" markerWidth="8" markerHeight="6" refX="7" refY="3" orient="auto">
      <path d="M0,0 L0,6 L8,3 z" fill="#475569"/>
    </marker>
    <marker id="p2-arr-blue" markerWidth="8" markerHeight="6" refX="7" refY="3" orient="auto">
      <path d="M0,0 L0,6 L8,3 z" fill="#2563EB"/>
    </marker>
    <marker id="p2-arr-violet" markerWidth="8" markerHeight="6" refX="7" refY="3" orient="auto">
      <path d="M0,0 L0,6 L8,3 z" fill="#7C3AED"/>
    </marker>
  </defs>

  <rect width="720" height="380" fill="#F8FAFC" rx="8"/>

  <!-- MessageService.send() at top -->
  <rect x="240" y="20" width="240" height="50" rx="8" fill="#1E293B" stroke="#334155" stroke-width="1.5"/>
  <text x="360" y="42" fill="white" font-size="13" font-weight="700" text-anchor="middle">MessageService.send()</text>
  <text x="360" y="58" fill="#94A3B8" font-size="10" text-anchor="middle">persist → all 9 message types</text>

  <!-- Branch lines -->
  <line x1="300" y1="70" x2="180" y2="130" stroke="#475569" stroke-width="1.5" stroke-dasharray="4,3"/>
  <line x1="420" y1="70" x2="540" y2="130" stroke="#475569" stroke-width="1.5" stroke-dasharray="4,3"/>

  <!-- Path 1: ChannelBackend Fan-Out -->
  <rect x="60" y="130" width="240" height="60" rx="8" fill="#EFF6FF" stroke="#2563EB" stroke-width="2"/>
  <text x="180" y="152" fill="#1D4ED8" font-size="12" font-weight="700" text-anchor="middle">ChannelBackend Fan-Out</text>
  <text x="180" y="168" fill="#3B82F6" font-size="9" text-anchor="middle">Per-channel · targeted · registered backends</text>
  <text x="180" y="180" fill="#93C5FD" font-size="9" text-anchor="middle">fanOut() via virtual threads, non-fatal</text>

  <!-- Path 1 sub-boxes -->
  <rect x="24" y="226" width="100" height="36" rx="6" fill="#DBEAFE" stroke="#93C5FD" stroke-width="1"/>
  <text x="74" y="241" fill="#1E40AF" font-size="9" font-weight="600" text-anchor="middle">Claudony</text>
  <text x="74" y="254" fill="#3B82F6" font-size="8" text-anchor="middle">ClaudonyBackend</text>

  <rect x="138" y="226" width="100" height="36" rx="6" fill="#DBEAFE" stroke="#93C5FD" stroke-width="1"/>
  <text x="188" y="241" fill="#1E40AF" font-size="9" font-weight="600" text-anchor="middle">Slack / WhatsApp</text>
  <text x="188" y="254" fill="#3B82F6" font-size="8" text-anchor="middle">ConnectorBackend</text>

  <rect x="38" y="280" width="224" height="36" rx="6" fill="#EFF6FF" stroke="#93C5FD" stroke-width="1" stroke-dasharray="4,3"/>
  <text x="150" y="295" fill="#93C5FD" font-size="9" font-weight="600" text-anchor="middle">future backends</text>
  <text x="150" y="308" fill="#BFDBFE" font-size="8" text-anchor="middle">any ChannelBackend impl</text>

  <!-- Arrows to Path 1 sub-boxes -->
  <line x1="100" y1="190" x2="74" y2="226" stroke="#2563EB" stroke-width="1.5" marker-end="url(#p2-arr-blue)"/>
  <line x1="160" y1="190" x2="188" y2="226" stroke="#2563EB" stroke-width="1.5" marker-end="url(#p2-arr-blue)"/>
  <line x1="130" y1="190" x2="150" y2="280" stroke="#93C5FD" stroke-width="1" stroke-dasharray="4,3"/>

  <!-- Path 2: MessageObserver -->
  <rect x="420" y="130" width="240" height="60" rx="8" fill="#F5F3FF" stroke="#7C3AED" stroke-width="2"/>
  <text x="540" y="152" fill="#5B21B6" font-size="12" font-weight="700" text-anchor="middle">MessageObserver</text>
  <text x="540" y="168" fill="#7C3AED" font-size="9" text-anchor="middle">Global · all messages · SPI · pluggable transport</text>
  <text x="540" y="180" fill="#A78BFA" font-size="9" text-anchor="middle">Instance&lt;MessageObserver&gt; iteration</text>

  <!-- Path 2 sub-boxes -->
  <rect x="424" y="226" width="110" height="36" rx="6" fill="#EDE9FE" stroke="#A78BFA" stroke-width="1"/>
  <text x="479" y="241" fill="#4C1D95" font-size="9" font-weight="600" text-anchor="middle">InProcessMessageBus</text>
  <text x="479" y="254" fill="#7C3AED" font-size="8" text-anchor="middle">CDI · LOCAL scope</text>

  <rect x="546" y="226" width="110" height="36" rx="6" fill="#EDE9FE" stroke="#A78BFA" stroke-width="1" stroke-dasharray="4,3"/>
  <text x="601" y="241" fill="#A78BFA" font-size="9" font-weight="600" text-anchor="middle">KafkaMessageBus</text>
  <text x="601" y="254" fill="#C4B5FD" font-size="8" text-anchor="middle">Kafka · CLUSTER scope</text>

  <rect x="438" y="280" width="224" height="36" rx="6" fill="#F5F3FF" stroke="#A78BFA" stroke-width="1" stroke-dasharray="4,3"/>
  <text x="550" y="295" fill="#A78BFA" font-size="9" font-weight="600" text-anchor="middle">WebSocketMessageBus · WebhookMessageBus</text>
  <text x="550" y="308" fill="#C4B5FD" font-size="8" text-anchor="middle">future · CLUSTER scope</text>

  <!-- Arrows to Path 2 sub-boxes -->
  <line x1="500" y1="190" x2="479" y2="226" stroke="#7C3AED" stroke-width="1.5" marker-end="url(#p2-arr-violet)"/>
  <line x1="560" y1="190" x2="601" y2="226" stroke="#A78BFA" stroke-width="1" stroke-dasharray="4,3"/>
  <line x1="540" y1="190" x2="550" y2="280" stroke="#A78BFA" stroke-width="1" stroke-dasharray="4,3"/>

  <!-- Comparison table -->
  <rect x="50" y="334" width="280" height="30" rx="4" fill="#F1F5F9"/>
  <text x="190" y="353" fill="#475569" font-size="9" text-anchor="middle">per-channel · requires registration · targeted push</text>

  <rect x="390" y="334" width="280" height="30" rx="4" fill="#F1F5F9"/>
  <text x="530" y="353" fill="#475569" font-size="9" text-anchor="middle">global · all channels · all messages · observer filters</text>
</svg>

**ChannelBackend fan-out** is selective. You register a backend on a specific channel;
`fanOut()` calls `post()` on it for every message on that channel. The backend knows
the channel, knows the direction, and can be anywhere — same JVM (CDI bean), remote
service (HTTP call), external platform (WhatsApp API). `ClaudonyChannelBackend` is
the canonical example: it receives every message on a channel and pushes it to the
Claudony conversation panel, wherever that panel lives.

**MessageObserver** is a broadcast. Every persisted message — all 9 types, all
channels — fires all registered `MessageObserver` implementations. Observers filter
themselves. This is the pattern for cross-cutting harness concerns: a clinical
harness observing every channel for PI responses, an AML harness monitoring for
escalation triggers. The observer doesn't need to know which channels to watch.

---

## The MessageObserver SPI

`MessageObserver` is defined in `casehub-qhorus-api` — the contract layer with no
runtime dependencies:

```java
@FunctionalInterface
public interface MessageObserver {
    void onMessage(MessageReceivedEvent event);
    default Scope scope() { return Scope.LOCAL; }
    enum Scope { LOCAL, CLUSTER }
}
```

`LOCAL` means delivery within the same JVM — zero serialisation, zero network
latency. `CLUSTER` means cross-process delivery via whatever transport the
implementation uses.

`MessageService.send()` uses `Instance<MessageObserver>` to dispatch to all
registered implementations after persistence:

```java
@Inject Instance<MessageObserver> observers;

// after messageStore.put(message)
String content = type == MessageType.EVENT ? null : message.content;
var event = new MessageReceivedEvent(ch.name, ch.id, type, sender, corrId, content);
for (var obs : observers) {
    try { obs.onMessage(event); }
    catch (Exception e) { LOG.warnf("MessageObserver %s failed: %s",
                                    obs.getClass().getSimpleName(), e.getMessage()); }
}
```

Failures are non-fatal and non-propagating — Dead Letter semantics. A broken
remote observer does not affect message persistence or any other observer.

### What MessageReceivedEvent carries

```java
public record MessageReceivedEvent(
    String channelName,   // for name-based routing (e.g. clinical: parse deviationId)
    UUID   channelId,     // for reliable programmatic lookup
    MessageType messageType,
    String senderId,
    String correlationId, // nullable — links response to originating COMMAND
    String content        // null for EVENT — see PP-20260508-90428f
) {}
```

EVENT content is always null. The telemetry fields (tool_name, duration_ms, token_count)
live in the ledger entry, not the notification event.

### The default implementation

`InProcessMessageBus` is the `@DefaultBean` — the fast path for embedded harnesses:

```java
@DefaultBean @ApplicationScoped
class InProcessMessageBus implements MessageObserver {
    @Inject Event<MessageReceivedEvent> cdiEvent;

    @Override
    public void onMessage(MessageReceivedEvent event) {
        cdiEvent.fireAsync(event)
                .exceptionally(t -> { LOG.warn("CDI observer failed", t); return null; });
    }
}
```

Harness code observes via `@ObservesAsync`:

```java
void onPiResponse(@ObservesAsync MessageReceivedEvent event) {
    if (event.messageType() != MessageType.DONE &&
        event.messageType() != MessageType.DECLINE) return;
    // ...
}
```

Zero configuration. Zero network. Zero serialisation.

---

## Transport Scope: LOCAL vs CLUSTER

This is the same distinction Vert.x draws between local consumers (in-JVM only)
and cluster-wide consumers (propagated across nodes). The scope declaration on
`MessageObserver` lets qhorus — and operators — understand what's registered.

<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 720 320" font-family="Inter, ui-sans-serif, system-ui, sans-serif">
  <defs>
    <marker id="sc-arr" markerWidth="8" markerHeight="6" refX="7" refY="3" orient="auto">
      <path d="M0,0 L0,6 L8,3 z" fill="#64748B"/>
    </marker>
    <marker id="sc-arr-v" markerWidth="8" markerHeight="6" refX="7" refY="3" orient="auto">
      <path d="M0,0 L0,6 L8,3 z" fill="#7C3AED"/>
    </marker>
    <marker id="sc-arr-o" markerWidth="8" markerHeight="6" refX="7" refY="3" orient="auto">
      <path d="M0,0 L0,6 L8,3 z" fill="#EA580C"/>
    </marker>
  </defs>

  <rect width="720" height="320" fill="#F8FAFC" rx="8"/>

  <!-- SPI centre -->
  <rect x="260" y="20" width="200" height="50" rx="8" fill="#1E293B" stroke="#334155" stroke-width="1.5"/>
  <text x="360" y="42" fill="white" font-size="13" font-weight="700" text-anchor="middle">MessageObserver SPI</text>
  <text x="360" y="58" fill="#94A3B8" font-size="10" text-anchor="middle">casehub-qhorus-api</text>

  <!-- LOCAL side -->
  <rect x="20" y="110" width="300" height="44" rx="8" fill="#EDE9FE" stroke="#7C3AED" stroke-width="2"/>
  <text x="170" y="128" fill="#4C1D95" font-size="11" font-weight="700" text-anchor="middle">InProcessMessageBus</text>
  <text x="170" y="146" fill="#6D28D9" font-size="9" text-anchor="middle">Scope.LOCAL · @DefaultBean · zero overhead</text>

  <!-- Arrow SPI to LOCAL -->
  <line x1="280" y1="70" x2="220" y2="110" stroke="#7C3AED" stroke-width="2" marker-end="url(#sc-arr-v)"/>

  <!-- LOCAL downstream -->
  <rect x="20" y="194" width="300" height="36" rx="6" fill="#F5F3FF" stroke="#A78BFA" stroke-width="1"/>
  <text x="170" y="209" fill="#5B21B6" font-size="10" font-weight="600" text-anchor="middle">CDI Event&lt;MessageReceivedEvent&gt;</text>
  <text x="170" y="223" fill="#7C3AED" font-size="9" text-anchor="middle">@ObservesAsync in clinical / aml / devtown</text>

  <!-- Arrow LOCAL to CDI -->
  <line x1="170" y1="154" x2="170" y2="194" stroke="#7C3AED" stroke-width="1.5" marker-end="url(#sc-arr-v)"/>

  <!-- CLUSTER side -->
  <rect x="400" y="110" width="300" height="44" rx="8" fill="#FFF7ED" stroke="#EA580C" stroke-width="2" stroke-dasharray="6,3"/>
  <text x="550" y="128" fill="#9A3412" font-size="11" font-weight="700" text-anchor="middle">Network Transport Impls</text>
  <text x="550" y="146" fill="#C2410C" font-size="9" text-anchor="middle">Scope.CLUSTER · casehub-connectors or host app</text>

  <!-- Arrow SPI to CLUSTER -->
  <line x1="440" y1="70" x2="500" y2="110" stroke="#EA580C" stroke-width="2" stroke-dasharray="4,2" marker-end="url(#sc-arr-o)"/>

  <!-- CLUSTER downstream - three boxes -->
  <rect x="400" y="194" width="84" height="50" rx="6" fill="#FEF3C7" stroke="#FCD34D" stroke-width="1" stroke-dasharray="4,3"/>
  <text x="442" y="213" fill="#92400E" font-size="9" font-weight="600" text-anchor="middle">Kafka</text>
  <text x="442" y="225" fill="#B45309" font-size="8" text-anchor="middle">MessageBus</text>
  <text x="442" y="237" fill="#D97706" font-size="8" text-anchor="middle">durable log</text>

  <rect x="500" y="194" width="84" height="50" rx="6" fill="#FEF3C7" stroke="#FCD34D" stroke-width="1" stroke-dasharray="4,3"/>
  <text x="542" y="213" fill="#92400E" font-size="9" font-weight="600" text-anchor="middle">WebSocket</text>
  <text x="542" y="225" fill="#B45309" font-size="8" text-anchor="middle">MessageBus</text>
  <text x="542" y="237" fill="#D97706" font-size="8" text-anchor="middle">real-time push</text>

  <rect x="600" y="194" width="84" height="50" rx="6" fill="#FEF3C7" stroke="#FCD34D" stroke-width="1" stroke-dasharray="4,3"/>
  <text x="642" y="213" fill="#92400E" font-size="9" font-weight="600" text-anchor="middle">Webhook</text>
  <text x="642" y="225" fill="#B45309" font-size="8" text-anchor="middle">MessageBus</text>
  <text x="642" y="237" fill="#D97706" font-size="8" text-anchor="middle">HTTP callback</text>

  <!-- Arrows CLUSTER to impls -->
  <line x1="480" y1="154" x2="442" y2="194" stroke="#EA580C" stroke-width="1" stroke-dasharray="4,2" marker-end="url(#sc-arr-o)"/>
  <line x1="545" y1="154" x2="542" y2="194" stroke="#EA580C" stroke-width="1" stroke-dasharray="4,2" marker-end="url(#sc-arr-o)"/>
  <line x1="590" y1="154" x2="642" y2="194" stroke="#EA580C" stroke-width="1" stroke-dasharray="4,2" marker-end="url(#sc-arr-o)"/>

  <!-- Labels -->
  <text x="170" y="268" fill="#64748B" font-size="9" text-anchor="middle">Ships in #153 (today)</text>
  <text x="550" y="268" fill="#94A3B8" font-size="9" text-anchor="middle">Future — casehub-connectors / SmallRye</text>

  <!-- Divider -->
  <line x1="370" y1="100" x2="370" y2="290" stroke="#E2E8F0" stroke-width="1" stroke-dasharray="4,3"/>
</svg>

The left side ships today. The right side is plugged in when the deployment topology
requires it — no changes to qhorus core, no changes to harness observers. A
`KafkaMessageBus` registered as an additional `@ApplicationScoped` bean fires
alongside the CDI bus. Both run. Kafka consumers and CDI observers coexist.

Multiple observers of the same scope can coexist. `Instance<MessageObserver>` iterates
all of them. There is no "replace the CDI bus with Kafka" — you add the Kafka bus
alongside it and let consumers decide which delivery mechanism they want.

---

## A2A Integration

The Agent-to-Agent protocol is one of several inbound paths into qhorus. Messages
arriving via A2A go through `A2AChannelBackend`, which normalises them to the internal
speech-act type and calls `QhorusMcpTools.sendMessage()`. From there the flow is
identical to any other message — persistence, ledger, fan-out, observer notification.

<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 720 200" font-family="Inter, ui-sans-serif, system-ui, sans-serif">
  <defs>
    <marker id="a2a-arr" markerWidth="8" markerHeight="6" refX="7" refY="3" orient="auto">
      <path d="M0,0 L0,6 L8,3 z" fill="#475569"/>
    </marker>
  </defs>
  <rect width="720" height="200" fill="#F8FAFC" rx="8"/>

  <!-- A2A Agent -->
  <rect x="20" y="80" width="110" height="44" rx="6" fill="#0D9488" stroke="#0F766E" stroke-width="1.5"/>
  <text x="75" y="98" fill="white" font-size="10" font-weight="700" text-anchor="middle">A2A Agent</text>
  <text x="75" y="114" fill="#CCFBF1" font-size="8" text-anchor="middle">POST /a2a/message:send</text>

  <!-- A2AChannelBackend -->
  <rect x="160" y="80" width="130" height="44" rx="6" fill="#134E4A" stroke="#0D9488" stroke-width="1.5"/>
  <text x="225" y="98" fill="white" font-size="10" font-weight="700" text-anchor="middle">A2AChannelBackend</text>
  <text x="225" y="114" fill="#CCFBF1" font-size="8" text-anchor="middle">protocol bridge · actor resolution</text>

  <!-- QhorusMcpTools -->
  <rect x="320" y="80" width="130" height="44" rx="6" fill="#1E293B" stroke="#334155" stroke-width="1.5"/>
  <text x="385" y="98" fill="white" font-size="10" font-weight="700" text-anchor="middle">QhorusMcpTools</text>
  <text x="385" y="114" fill="#94A3B8" font-size="8" text-anchor="middle">sendMessage()</text>

  <!-- MessageService -->
  <rect x="480" y="80" width="110" height="44" rx="6" fill="#1E293B" stroke="#334155" stroke-width="1.5"/>
  <text x="535" y="98" fill="white" font-size="10" font-weight="700" text-anchor="middle">MessageService</text>
  <text x="535" y="114" fill="#94A3B8" font-size="8" text-anchor="middle">persist · notify</text>

  <!-- Channel/Ledger -->
  <rect x="620" y="55" width="82" height="36" rx="6" fill="#312E81" stroke="#4338CA" stroke-width="1"/>
  <text x="661" y="70" fill="white" font-size="9" font-weight="600" text-anchor="middle">Channel</text>
  <text x="661" y="83" fill="#A5B4FC" font-size="8" text-anchor="middle">+ Ledger</text>

  <rect x="620" y="107" width="82" height="36" rx="6" fill="#5B21B6" stroke="#7C3AED" stroke-width="1"/>
  <text x="661" y="122" fill="white" font-size="9" font-weight="600" text-anchor="middle">Observer</text>
  <text x="661" y="135" fill="#DDD6FE" font-size="8" text-anchor="middle">Notification</text>

  <!-- Arrows -->
  <line x1="130" y1="102" x2="160" y2="102" stroke="#475569" stroke-width="1.5" marker-end="url(#a2a-arr)"/>
  <line x1="290" y1="102" x2="320" y2="102" stroke="#475569" stroke-width="1.5" marker-end="url(#a2a-arr)"/>
  <line x1="450" y1="102" x2="480" y2="102" stroke="#475569" stroke-width="1.5" marker-end="url(#a2a-arr)"/>
  <line x1="590" y1="90" x2="620" y2="75" stroke="#475569" stroke-width="1.5" marker-end="url(#a2a-arr)"/>
  <line x1="590" y1="112" x2="620" y2="120" stroke="#7C3AED" stroke-width="1.5" marker-end="url(#a2a-arr)"/>

  <!-- HTTP label -->
  <text x="144" y="75" fill="#0D9488" font-size="8" text-anchor="middle">HTTP</text>
</svg>

`A2AChannelBackend` does the hard work: six-step sender identity resolution,
COMMAND/RESPONSE correlation, task state mapping via `CommitmentService`. Once
the message enters `MessageService`, the A2A origin is irrelevant — it's a speech
act like any other.

Outbound A2A — a qhorus agent responding to an external A2A caller — flows
through `A2AResource.getTask()`, which reads `CommitmentStore` to map the
internal commitment state to the A2A task state format.

---

## Why Two Paths and Not One

A natural question: why maintain both `ChannelBackend` fan-out and `MessageObserver`?
They're not redundant — they serve different roles.

**ChannelBackend** knows the channel. It's registered on a specific channel by ID.
It receives messages in context — it knows it's the WhatsApp group for case 4521,
the Claudony panel for session 99, the Slack thread for incident 7. This context
is essential for backends that route, transform, or present messages.

**MessageObserver** knows nothing about channels. It sees every message from every
channel. This is appropriate for cross-cutting concerns — a clinical harness
monitoring all oversight channels for PI decisions, an AML engine scanning all
channels for escalation triggers. Registering a `ChannelBackend` per channel for
this use case would require dynamic registration logic and channel enumeration;
`MessageObserver` handles it in one observer method.

The pattern is well established. The [Vert.x EventBus][vertx-eb] makes the same
distinction: local consumers (in-JVM, not cluster-propagated) vs cluster consumers
(distributed). Enterprise Integration Patterns calls the outer mechanism a
[Publish-Subscribe Channel][eip-pubsub] and the per-channel delivery a
[Channel Adapter][eip-adapter].

---

## Pattern References

This architecture implements several established patterns:

| Pattern | Source | Where in qhorus |
|---|---|---|
| Publish-Subscribe Channel | EIP §Messaging Channels | `MessageObserver` broadcast |
| Channel Adapter | EIP §Messaging Channels | Each `MessageObserver` impl |
| Messaging Gateway | EIP §Messaging Endpoints | `MessageObserver` SPI decouples `MessageService` from transport |
| Dead Letter (non-propagating failure) | EIP §System Management | try-catch per observer, log and continue |
| Local vs Cluster Consumer | [Vert.x EventBus][vertx-eb] | `Scope.LOCAL` vs `Scope.CLUSTER` |
| Pluggable Transport Connector | [SmallRye Reactive Messaging][smallrye] | `MessageObserver` impls as transport connectors |

[vertx-eb]: https://vertx.io/docs/vertx-core/java/#event_bus
[eip-pubsub]: https://www.enterpriseintegrationpatterns.com/patterns/messaging/PublishSubscribeChannel.html
[eip-adapter]: https://www.enterpriseintegrationpatterns.com/patterns/messaging/ChannelAdapter.html
[smallrye]: https://quarkus.io/guides/kafka

---

## What Ships When

| Capability | Status |
|---|---|
| ChannelBackend fan-out (`fanOut()`, `ClaudonyChannelBackend`, connectors) | ✅ live |
| A2A protocol bridge (`A2AChannelBackend`, actor resolution) | ✅ live |
| `MessageReceivedEvent` record, `MessageObserver` SPI | 🔧 qhorus#153 |
| `InProcessMessageBus` (CDI default, `Scope.LOCAL`) | 🔧 qhorus#153 |
| `KafkaMessageBus`, `WebSocketMessageBus`, webhook impl | ⬜ future |
| SmallRye / MicroProfile Reactive Messaging bridge | ⬜ future |

The SPI is the seam. When a Claudony terminal on a remote machine needs push
notification beyond what `ClaudonyChannelBackend` already provides, a
`WebSocketMessageBus` implementing `MessageObserver` is the answer — registered
as a CDI bean, picked up by `Instance<MessageObserver>` alongside the CDI bus,
no changes anywhere else.
