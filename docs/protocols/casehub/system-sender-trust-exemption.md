# system-sender-trust-exemption

**Summary:** System senders (sender contains `:`) bypass ObligorTrustPolicy when target is set on COMMANDs

**Applies to:** `MessageService.dispatch()`, `ReactiveMessageService.doDispatch()` — the ObligorTrustPolicy enforcement gate

## Rule

The trust gate condition for COMMAND messages with a named target must include a sender exemption:

```java
if (ch != null && dispatch.type() == MessageType.COMMAND
        && dispatch.target() != null
        && !dispatch.target().contains(":")
        && !dispatch.sender().contains(":")) {   // ← system sender exemption
```

System senders (e.g. `casehub-engine:orchestrator`, `system:watchdog`) bypass the trust check because the engine's provisioning decision IS the trust authority for worker assignments. The trust gate exists for peer-agent COMMANDs where one agent commands another — those require trust verification.

## Why

Without the sender exemption, setting `target` on engine-dispatched COMMANDs triggers `ObligorTrustPolicy.permits()`, which rejects the COMMAND when `min-obligor-trust > 0` and the target agent has no trust history. This is a false rejection — the engine already verified the agent through `AgentRoutingStrategy` before dispatching.

## Convention

The `:` delimiter in sender strings is the discriminator:
- `casehub-engine:orchestrator` → system sender (contains `:`) → exempt
- `system:watchdog` → system sender → exempt
- `agent-alpha` → peer agent (no `:`) → trust-gated

Both `MessageService` and `ReactiveMessageService` must apply this exemption identically.

Refs: openclaw#70, GE-20260719-44e9d9
