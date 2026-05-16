# Agent Argument Graphs — Design Exploration

**Date:** 2026-05-16  
**Status:** Draft for discussion  
**Context:** Qhorus deliberative layer — structured argumentation between agents and humans toward a shared, accountable decision

---

## What This Is

Qhorus already handles the *commitment* layer of multi-agent interaction: who promised what to whom, when, and what happened. What it does not yet handle is the *deliberative* layer — the structured back-and-forth that happens when agents are not executing instructions but are **reasoning together toward a decision**.

The argument graph is the infrastructure for that layer. Its primary purpose is accountability and explainability: producing a human-readable, human-verifiable record of *why* a conclusion was reached — not just *that* it was reached and *who* committed to it.

```
The normative ledger answers:  WHAT was decided | WHO committed to it
The argument graph answers:    WHY — the reasoning path that produced the conclusion
```

Together they close the accountability loop that consequential AI decisions require. A compliance officer, an architect reviewing a past decision, or a new agent joining a case in progress can ask: "why did they conclude this?" and get a structured answer — not a transcript they must reconstruct, but a record of every claim, every challenge, every concession, and every revision that produced the outcome.

---

## The Direct Parallel to the Normative Layer

The normative layer did not teach LLMs what a COMMAND or DECLINE is. They already understood those concepts from training on thirty years of speech act theory. What the normative layer did was provide **shared, standardised labels** so that every agent in the mesh uses the same word for the same concept. The record — and everything derived from it — follows naturally.

The argument layer works the same way.

LLMs already understand what a rebuttal is, what conceding a point means, what an assumption is and how it can be challenged. They reason about these things constantly. The argument layer standardises the vocabulary they use to express these moves, so that the logical structure of a deliberation is implicit in how agents write — and can be extracted, stored, and presented without requiring agents to build a graph manually in real time.

| Layer | Shared vocabulary | What it standardises | Infrastructure derives |
|---|---|---|---|
| Normative | COMMAND, DECLINE, HANDOFF, DONE… | What kind of *act* is being performed | Obligation lifecycle, causal chain, accountability |
| Argument | CLAIM, PRESUME, SUPPORT, REBUT, UNDERCUT, CONCEDE, REVISE | What kind of *logical move* is being made | Reasoning trail, concession history, verdict |

Both layers follow the same principle: **the LLM reasons; the infrastructure records and derives**. Neither layer changes how LLMs think. Both make the structure of that thinking legible to the mesh.

---

## Position in the Literature

A systematic search of academic literature (2023–2026) and open-source implementations reveals a rich body of related work — and a clear gap that this design occupies.

### What already exists

**Typed epistemic acts in structured deliberation.** The closest academic precedent is *"From Debate to Deliberation: Structured Collective Reasoning with Typed Epistemic Acts"* (arXiv 2603.11781, March 2026), which defines 14 typed moves (propose, challenge, bridge, synthesize, and others) that agents exchange in structured phases with a guaranteed termination condition. Evaluated on 45 tasks using Gemini 2.5 Flash; outperforms unstructured debate on non-routine tasks. This validates the typed-vocabulary approach directly.

**Argumentation frameworks on LLM output.** *ArgLLMs* (arXiv 2405.02079, KR2025) and *ArgEval* (arXiv 2603.14643) augment LLMs with Dung-style abstract argumentation frameworks. A formal solver computes winning arguments from the LLM's output; users can explicitly attack a supporting argument and the system recalculates the claim's strength. Strong on formal explainability and contestability. The difference from this design: they externalise the argument framework as a post-hoc or parallel structure computed from LLM text. This design *embeds the vocabulary inside the conversation*, so extraction is pattern matching on labelled moves rather than semantic inference.

**Structured roles in code review.** *VulTrial* (ICSE 2026, arXiv 2505.10961) applies a courtroom structure to vulnerability detection: security researcher (prosecutor), code author (defense), moderator (judge), review board (jury). Adversarial framing roughly doubles single-agent detection efficacy. *MuCoLD* (arXiv 2403.14274) uses tester/developer roles in a three-stage discussion for vulnerability classification, improving F1 by 28.1% over single-agent baselines. Neither system uses an explicit shared vocabulary of reasoning-move types.

**Structured "logical certificates" in code review.** Meta's *Agentic Code Reasoning* (arXiv 2603.01896, March 2026) requires LLMs to fill in a structured certificate — premises, execution trace, formal conclusion — before issuing a verdict. Achieves 93% accuracy on patch equivalence. This is the same impulse as requiring CLAIM + SUPPORT + PRESUME before a conclusion: grounding is mandatory, assertion is not enough.

**Human-contestable AI reasoning.** *"Argumentative Human-AI Decision-Making: Toward AI Agents That Reason With Us, Not For Us"* (arXiv 2603.15946) is the clearest intellectual predecessor in spirit — a vision paper explicitly calling for embedded argumentative dialogue where decisions are revisable and contestable. This design is one concrete realisation of what that paper proposes.

**Anti-conformity mechanisms.** *"Can LLM Agents Really Debate?"* (arXiv 2511.07784) identifies "minority correction asymmetry" — agents maintain correct minority positions but fail to correct incorrect majorities under social conformity pressure. *Free-MAD* (arXiv 2509.11035) addresses this with explicit anti-conformity prompting. The argument vocabulary addresses the same problem structurally: an agent cannot silently conform without registering a CONCEDE or REVISE move. Conformity becomes a visible, recorded act.

**Ratification of argument structure.** *"Dialectical Reconciliation via Structured Argumentative Dialogues"* (KR 2024, arXiv 2306.14694) formalises the two-party reconciliation dialogue — an explainer and explainee resolve a knowledge discrepancy through structured moves, with proven termination guarantees. This is the closest formal grounding for the ratification step.

### What does not exist

A systematic search found no published system or paper that combines all three of the following simultaneously:

1. **Vocabulary embedded in natural language** — agents label their reasoning moves inline, using the same text channel as the conversation, without a format shift or separate tool call
2. **Checkpoint ratification** — a structured moment where agents mutually confirm that the extracted argument structure accurately represents their deliberation, before it becomes the official record
3. **Infrastructure-level enforcement with an immutable audit ledger** — the ratified graph is a deontic artefact, tamper-evidently recorded alongside the commitment trail

Each element appears in isolation. The vocabulary-first, checkpoint-ratification, infrastructure-enforced combination is the novel contribution of this design.

---

## The Argument Vocabulary

Seven moves cover the logical structure of deliberative conversation. The vocabulary is intentionally close to natural language — agents are not asked to produce formal logic, only to label the move they are making.

### The seven moves

| Move | When to use it |
|---|---|
| **CLAIM** | Asserting a position with stated reasoning |
| **PRESUME** | Stating an assumption the argument depends on — explicitly inviting correction |
| **SUPPORT** | Backing a CLAIM with evidence or further reasoning |
| **REBUT** | Directly contradicting a specific CLAIM |
| **UNDERCUT** | Challenging the assumption or evidence behind a CLAIM, not the CLAIM itself |
| **CONCEDE** | Explicitly withdrawing a prior position, with the reason |
| **REVISE** | Updating a prior CLAIM in light of a successful UNDERCUT or REBUT, with explanation |

### What makes a good argument — with examples

The vocabulary is only useful if agents use it precisely. The difference between a good and bad argument move is the difference between a record that a human can verify and one they cannot.

---

**CLAIM**

Good:
> "I CLAIM approach A is preferable: its layered architecture isolates the persistence concern, making each layer independently testable without requiring the full stack."

Bad:
> "Approach A is obviously better."

*Why it matters:* A bare assertion gives no basis for challenge. Without a stated reason, there is nothing for REBUT or UNDERCUT to attach to. The record cannot show why the claim was accepted or rejected — only that it was asserted.

---

**PRESUME**

Good:
> "PRESUMING we want to avoid breaking existing API contracts — correct me if that assumption is wrong — approach B fits the constraint better than A, since it wraps rather than replaces the existing interface."

Bad:
> "Since we don't want to break the API, approach B is better."

*Why it matters:* The bad form treats an assumption as established fact. The reader cannot tell that the conclusion depends on a premise that might be wrong. If the assumption is later found to be incorrect, there is no record that it was the hinge point — and no clear UNDERCUT target.

---

**SUPPORT**

Good:
> "SUPPORTING my CLAIM for A: the load tests in artefact ref-7c2b show 40% lower p99 latency at the expected peak concurrency of 500 requests per second."

Bad:
> "A is faster."

*Why it matters:* The good form links evidence to a specific claim and makes that evidence auditable (via artefact ref). The bad form cannot be verified, challenged, or carried forward as part of the reasoning record.

---

**REBUT**

Good:
> "I REBUT the CLAIM that approach C minimises operational risk — it introduces an async event boundary that has been the source of the three most recent production incidents in this codebase (see postmortems in ref-3f4a)."

Bad:
> "That's wrong."

*Why it matters:* A REBUT without identifying which claim it targets and why it fails is invisible to the argument record. "That's wrong" has no anchor, no evidence, and cannot be evaluated or responded to precisely.

---

**UNDERCUT**

Good:
> "I UNDERCUT the PRESUMPTION that we cannot change existing code — in the architecture review last week we explicitly agreed that we should change anything necessary for better long-term robustness."

Bad:
> "Your assumption is wrong."

*Why it matters:* UNDERCUT targets the *foundation* of an argument — the assumption or evidence that makes it work — rather than the conclusion itself. An agent that successfully UNCEDEs a PRESUMPTION does not need to REBUT the CLAIM directly; if the foundation fails, the CLAIM built on it fails too. Confusing UNDERCUT with REBUT produces a weaker challenge and a murkier record.

---

**CONCEDE**

Good:
> "I CONCEDE that the operational simplicity of approach C is a real advantage and I previously understated it. My earlier CLAIM about C's scalability limits was based on load estimates that you have shown are too conservative."

Bad:
> "OK fine, maybe C is better."

*Why it matters:* An explicit CONCEDE identifies what is being withdrawn and why. It creates a record of which position the agent held, what caused them to release it, and what evidence or argument was decisive. "OK fine" is invisible — the record cannot show that a concession happened, let alone what caused it.

---

**REVISE**

Good:
> "I REVISE my CLAIM for approach A — your UNDERCUT of my change-constraint PRESUMPTION removes A's main advantage. I now CLAIM approach B offers the best balance: it achieves A's architectural goals while fitting the actual constraints we are working under."

Bad:
> "Actually I think B is better now."

*Why it matters:* A REVISE links the updated position to the move that caused it. The record shows the chain: PRESUME → UNDERCUT → REVISE. Without this linkage, position changes appear arbitrary. With it, the reasoning trail is complete.

---

## How the Vocabulary Produces a Graph

Because agents use the vocabulary as they argue, the logical structure of a deliberation is already implicit in the text. The argument graph is not built separately in real time — it is extracted from the conversation at checkpoint moments.

An agent arguing with this vocabulary naturally produces text like:

> "I CLAIM approach A is best given the robustness requirement. PRESUMING we have latitude to refactor the existing persistence layer — correct me if wrong. SUPPORTING this: A's architecture separates concerns in a way that makes each layer independently testable."

The extraction step maps this directly:

```
CLAIM      → ArgumentNode { claim: "approach A is best", author: agent, basis: ASSERTED }
PRESUME    → ArgumentNode { claim: "we have refactoring latitude", basis: PRESUMPTION }
SUPPORT    → SupportEdge  { from: support-node, to: claim-node, type: BACKING }
```

When a human responds:

> "I UNDERCUT that PRESUMPTION — we agreed last week we can change anything for better architecture."

The graph gains:

```
UNDERCUT   → AttackEdge { from: undercut-node, to: presume-node, type: UNDERCUT }
```

And when the agent responds:

> "I CONCEDE the PRESUMPTION. I REVISE my CLAIM to approach B..."

```
CONCEDE    → retract { argument: presume-node, reason: "presumption undercut" }
REVISE     → ArgumentNode { claim: "approach B is best", basis: INFERRED, supersedes: claim-node }
```

The vocabulary is the graph. Extraction formalises what is already there.

---

## Audit Checkpoints

The graph surfaces at **checkpoint moments** — natural pause points where the argument structure has shifted or is about to produce a conclusion. Not on every exchange.

**Checkpoint triggers:**

- Before a final recommendation or decision is made
- When a PRESUME has been successfully UNDERCUT (a foundational assumption has changed)
- When an agent issues a REVISE (the position has materially shifted)
- When a new participant joins a deliberation in progress
- When any participant explicitly requests one

At a checkpoint, the agent produces a structured summary using the argument vocabulary:

---

*Argument checkpoint — persistence layer approach:*

> CLAIM [agent]: approach A is best, on grounds of testability and separation of concerns  
> SUPPORT: load tests in ref-7c2b show 40% lower p99 latency  
> PRESUME [agent]: we cannot refactor the existing persistence layer  
> ← UNDERCUT [you]: we agreed last week we can change anything for better architecture  
> CONCEDE [agent]: the PRESUMPTION was wrong  
> REVISED CLAIM [agent]: approach B — achieves A's goals within the actual constraints  
>  
> Standing: A's latency advantage stands; the constraint argument for C has not been rebutted  
>  
> *Does this represent your shared understanding?*

---

This is human-readable, human-verifiable, and convertible to a graph without interpretation. The agent constructs it from its context window — cheap to generate, cheap to review because the participant was just in the conversation.

---

## Ratification

The checkpoint is a draft. Ratification is the act of agreeing that it accurately represents the deliberation.

Each participant — agent or human — either ratifies ("yes, this captures what I argued and conceded") or raises a correction ("I did not concede that; I said X only under assumption Y"). A correction uses the vocabulary to identify what is wrong. The corrected checkpoint is re-presented. Ratification is complete when all participants have confirmed.

This is what transforms the argument graph from a system-produced log into a **mutually agreed record**. When accountability is demanded later — "why did you choose this architecture?", "what was the basis for this risk assessment?" — the answer is not "here is what the agents said" but "here is the record we all agreed is accurate, at the time."

A ratified argument graph carries the same deontic weight as a signed DONE in the commitment layer. It is the formal close of the deliberation.

---

## Worked Examples

These examples show the vocabulary in use in real Java development conversations. The argument structure is woven into natural language — not formal logic, not a separate annotation layer.

---

### Example 1: Human + agent — Persistence layer design (Quarkus extension)

*Context: Designing the persistence approach for a new `WorkItem` entity in a Quarkus extension that will sit alongside `ChannelStore`, `MessageStore`, and `InstanceStore`.*

---

**Human:** We need to add a WorkItem entity. What persistence approach should we use?

**Agent:** Three approaches worth considering:

**Approach A — Panache active record**

I CLAIM this is the simplest Quarkus-idiomatic approach:
```java
@Entity
public class WorkItem extends PanacheEntity {
    public String title;
    public WorkItemStatus status;
}
```
SUPPORTING: Zero additional beans. `WorkItem.findById(id)` and `workItem.persist()` are available immediately. Standard Quarkus documentation pattern.
PRESUME — correct me if wrong — that we are not planning consumer unit tests that run without a Quarkus container.

**Approach B — PanacheRepository**
```java
@ApplicationScoped
public class WorkItemRepository implements PanacheRepository<WorkItem> {
    public List<WorkItem> findByStatus(WorkItemStatus s) {
        return find("status", s).list();
    }
}
```
SUPPORTING: Separates query logic from the entity. Easier to mock in unit tests than active record.

**Approach C — Store SPI (interface + JPA implementation)**
```java
public interface WorkItemStore {
    WorkItem findById(UUID id);
    void save(WorkItem item);
    List<WorkItem> findByStatus(WorkItemStatus status);
}
@ApplicationScoped
public class JpaWorkItemStore implements WorkItemStore { ... }
```
SUPPORTING: Maximally testable — the interface can be backed by `InMemoryWorkItemStore` in unit tests with no Quarkus context required.

My CLAIM for A holds if simplicity is the priority. I would REVISE to C if the extension needs to support the testing module pattern.

---

**Human:** Why A? We use the Store SPI pattern everywhere in this extension.

**Agent:** I UNDERCUT my own PRESUME. I assumed active record was the default preference, but looking at `ChannelStore`, `MessageStore`, `InstanceStore`, and `DataStore` — they all follow the Store SPI pattern: interface in `api/`, JPA implementation in `runtime/`, `InMemory*Store` in `testing/`. That is the established convention.

I CONCEDE my CLAIM for A — it was based on a PRESUME that does not hold in this codebase.

I REVISE: approach C is correct. It aligns with the existing convention, enables `InMemoryWorkItemStore` for consumer unit tests, and is consistent with how the `casehub-qhorus-testing` module provides test doubles for all other stores.

---

*Audit checkpoint — WorkItem persistence approach:*

> CLAIM [agent]: approach A (active record) initially favoured on simplicity grounds  
> PRESUME [agent]: consumer unit tests without a Quarkus container are not a requirement  
> ← UNDERCUT [you]: Store SPI is the established pattern here — ChannelStore, MessageStore, InstanceStore, DataStore all follow it  
> CONCEDE [agent]: the PRESUME was wrong for this codebase  
> REVISED CLAIM [agent]: approach C (Store SPI) — aligns with convention, enables InMemory testing alternative  
>
> Accepted: approach C  
> Not rebutted on technical merit but superseded by convention: approaches A and B  
>
> *Does this represent our shared understanding?*

**Human:** Yes, that's right.

*[Graph ratified. WorkItem persistence approach recorded.]*

---

### Example 2: Multi-agent — Channel gateway fan-out delivery (two review agents)

*Context: Two review agents debate the delivery semantics of `ChannelGateway.fanOut()` when dispatching to multiple backends. The human observes and ratifies.*

*Channel: `design-review/gateway-fanout`*
*Motion: adopt async fan-out for ChannelGateway backend dispatch*

---

**architecture-agent:**

I CLAIM synchronous fan-out is preferable for the initial implementation:
```java
public void fanOut(Message message) {
    backends.forEach(backend -> backend.post(message));
}
```
SUPPORTING: Errors surface immediately and propagate naturally to the caller. The caller knows exactly which backend failed. No thread pool sizing required.
PRESUME — correct me if wrong — that backends are expected to complete quickly (under 100ms) and the backend set does not include slow external integrations.

---

**reliability-agent:**

I REBUT the CLAIM for synchronous fan-out on reliability grounds: if one backend is slow or unresponsive, it blocks delivery to all subsequent backends in the list iteration order. In a deployment with Slack, Webhook, and A2A backends, a Slack API timeout delays the A2A backend notification by the full Slack timeout duration — which can be 30 seconds. This is a reliability hazard that gets worse as the backend set grows.

I CLAIM async fan-out using `ManagedExecutor` is more robust:
```java
public void fanOut(Message message) {
    backends.forEach(b -> executor.execute(() -> b.post(message)));
}
```
SUPPORTING: Each backend runs independently. One slow backend cannot starve others. Delivery latency is bounded by the slowest backend, not their sum.

---

**architecture-agent:**

I UNDERCUT your CLAIM on the async approach on transaction-ordering grounds. `fanOut()` is called within the `MessageService.send()` transaction boundary. Dispatching backend `post()` calls via `ManagedExecutor` runs them outside that transaction — which means a backend could receive and act on a message before the `MessageLedgerEntry` is committed to the ledger. This violates a core normative guarantee: backends must not see a message that has not yet been tamper-evidently recorded.

PRESUME — and I expect this is uncontested — that backends must not receive messages before the corresponding ledger entry commits.

---

**reliability-agent:**

I CONCEDE the transaction ordering point — this is a real violation. An async backend that receives a message and queries `list_ledger_entries` before the outer transaction commits would find no record of that message. That breaks the normative audit guarantee.

I REVISE: async dispatch is still the right approach, but deferred to after the transaction commits using the Quarkus `TransactionPhase.AFTER_SUCCESS` event pattern:

```java
// MessageService fires this event transactionally
void send(...) {
    // ... persist Message and LedgerEntry ...
    Event<MessageFanOutEvent> event = ...; // @Observes(during = AFTER_SUCCESS)
}

// Gateway observes only after commit
void onFanOut(@Observes(during = TransactionPhase.AFTER_SUCCESS)
              MessageFanOutEvent event) {
    backends.forEach(b -> executor.execute(() -> b.post(event.message())));
}
```
SUPPORTING: The `AFTER_SUCCESS` observer fires only after the outer transaction commits successfully. The ledger entry exists before any backend receives the message. Backend isolation is preserved. The normative ordering guarantee holds.

---

**architecture-agent:**

I SUPPORT the REVISED approach. The `AFTER_SUCCESS` pattern addresses the transaction ordering concern precisely. I CONCEDE my CLAIM for synchronous fan-out — the async-after-commit design is superior on both reliability and normative-correctness dimensions.

---

*Audit checkpoint — gateway fan-out delivery:*

> CLAIM [architecture-agent]: synchronous fan-out favoured — simplicity, immediate error propagation  
> PRESUME [architecture-agent]: backends are fast (< 100ms), no slow external integrations  
> REBUT [reliability-agent]: slow backends block all others; delivery latency compounds with backend count  
> CLAIM [reliability-agent]: async fan-out via ManagedExecutor — independent, bounded delivery  
> UNDERCUT [architecture-agent]: async dispatch runs outside the MessageService transaction — backends could receive messages before the LedgerEntry commits  
> PRESUME [architecture-agent]: backends must not see messages before ledger commit — uncontested  
> CONCEDE [reliability-agent]: transaction ordering violation is real  
> REVISED CLAIM [reliability-agent]: async fan-out deferred to AFTER_SUCCESS transaction phase  
> SUPPORT [architecture-agent]: AFTER_SUCCESS addresses the ordering concern  
> CONCEDE [architecture-agent]: synchronous fan-out superseded  
>
> Accepted: async fan-out with AFTER_SUCCESS transaction phase deferral  
> Rejected: bare synchronous fan-out; bare ManagedExecutor async (transaction ordering violation)  
> Uncontested presumption: backends must not receive messages before LedgerEntry commits  
>
> *Does this represent the shared understanding of both agents and the human reviewer?*

**Human:** Yes. And note that the uncontested presumption should be captured as a design constraint in the gateway implementation notes.

*[Graph ratified with annotation. Fan-out design recorded. Design constraint propagated to implementation notes.]*

---

### What the examples show

Both examples demonstrate the same pattern:

1. The conversation is natural — the vocabulary labels moves without interrupting the flow
2. The PRESUME move is the most important: it surfaces hidden assumptions so they can be challenged directly rather than silently driving the conclusion
3. UNDERCUT is more precise than REBUT — it defeats the foundation, not just the claim
4. The CONCEDE + REVISE chain is what produces a better outcome than either starting position: the final solution (async-after-commit) was reached because the argument structure forced both assumptions into the open
5. The checkpoint is short — one paragraph — because the vocabulary already structured the conversation; extraction is summarisation, not reconstruction

Neither example required any infrastructure beyond a system prompt containing the vocabulary. The graph emerged from how the agents wrote.

---

## Integration with the Normative Layer

The argument graph sits above the message and commitment layers as a complementary, non-overlapping layer:

```
+-----------------------------------------------------------------------+
|  ARGUMENT GRAPH LAYER  (new)                                          |
|  WHY — the reasoning trail that produced the conclusion               |
|                                                                       |
|  ArgumentNode (claim, basis, author, timestamp)                      |
|  AttackEdge (REBUT | UNDERCUT) / SupportEdge (BACKING | CONVERGENT)  |
|  Checkpoint snapshots + Ratification records                          |
+-----------------------------------------------------------------------+
                    ↕  argument_refs in message content
+-----------------------------------------------------------------------+
|  COMMITMENT / OBLIGATION LAYER  (existing)                           |
|  WHO owes what to whom — resolved or stalled                         |
|                                                                       |
|  CommitmentStore — 7-state lifecycle                                 |
|  MessageLedgerEntry — SHA-256 tamper-evident record                  |
+-----------------------------------------------------------------------+
                    ↕  speech acts create/resolve commitments
+-----------------------------------------------------------------------+
|  MESSAGE / SPEECH ACT LAYER  (existing)                              |
|  WHAT was said: QUERY COMMAND RESPONSE STATUS DECLINE HANDOFF        |
|                 DONE FAILURE EVENT                                    |
+-----------------------------------------------------------------------+
```

The layers are loosely coupled. A deliberation channel uses all three: messages carry the conversation, the commitment layer records who committed to the conclusion, the argument graph records why. An operational channel — payment processing, status updates, sanctions screening — uses only the bottom two. The argument graph is opt-in for deliberative contexts.

---

## Implementation Path

### Phase 1 — Vocabulary (zero new infrastructure)

Define the argument vocabulary formally: the seven moves, their definitions, what constitutes a good versus bad use of each, worked examples. Introduce this into the system prompt for deliberation contexts — the same way the normative layer vocabulary is available to agents via channel context.

Run conversations using the vocabulary. Observe whether the structure comes through clearly in the text. This is the validation step — it determines whether the vocabulary is right before any infrastructure is built. The normative layer was validated conceptually before the CommitmentStore existed. The argument layer follows the same path.

Deliverable: a vocabulary document and system prompt template. No code.

### Phase 2 — Graph storage and tooling

Implement the persistence layer and MCP tools that make the graph first-class infrastructure:

```
extract_argument_graph(channel_name, from_message_id, to_message_id)
  → draft ArgumentGraph from conversation segment, for ratification

ratify_graph(channel_name, participant_id, note)
  → confirm the graph accurately represents the deliberation

dispute_graph(argument_id, correction, participant_id)
  → raise a correction before ratification; restarts checkpoint

get_argument_graph(channel_name, as_of)
  → full graph with edges, authors, timestamps

get_argument_history(argument_id)
  → full event log: assertions, attacks, retractions, concessions
```

Explicit real-time tools (`assert_argument`, `attack_argument`, `retract_argument`) are also available as optional precision instruments for structured multi-agent deliberation, but not required for the human-agent conversation case.

Deliverable: ArgumentNode, AttackEdge, SupportEdge entities; MCP tools above; ratification lifecycle; integration with normative ledger for tamper evidence.

### Phase 3 — Constrained logic engine (future, optional)

Add grounded semantics labelling (Dung, 1995) running over the stored graph. Labels (ACCEPTED / REJECTED / UNDECIDED) become computed rather than asserted. Add cycle detection, convergence detection, and `DELIBERATION_STALLED` watchdog integration for large multi-agent deliberations.

This phase is not required for the accountability value. It adds formal guarantees for cases where the argument structure is complex enough that agents need computational help to navigate it.

---

## Open Questions

**1. Vocabulary precision vs. naturalness.** The seven moves need to be precise enough to produce clean extraction but natural enough that agents use them without the vocabulary feeling forced. Phase 1 is the experiment that answers this.

**2. PRESUME as the critical move.** Both worked examples show PRESUME + UNDERCUT as the hinge that produces a better outcome than either starting position. This may be the most important move to emphasise in the system prompt — surfacing hidden assumptions is where the most value is generated.

**3. Cross-deliberation references.** Can a CLAIM in one deliberation reference an accepted position from a prior one ("the design constraint ratified in argument-graph-47 applies here")? Enables cumulative organisational reasoning. Requires a stable argument registry beyond individual channels. A v2 concern.

**4. Annotation propagation.** In Example 2, the human added an annotation at ratification ("capture the uncontested presumption as a design constraint"). This suggests ratification may need a structured annotation mechanism, not just a yes/no confirmation. The annotation is itself a deontic act — a COMMAND to propagate a constraint — and should feed back into the commitment layer.

---

## Prior Art Reference

Key papers for further reading:

| Paper | Relevance |
|---|---|
| arXiv 2603.11781 — From Debate to Deliberation (2026) | Closest academic precedent: 14 typed epistemic acts, structured phases, termination guarantee |
| arXiv 2603.15946 — Argumentative Human-AI Decision-Making (2026) | Closest in vision: agents that reason *with* humans, not *for* them |
| arXiv 2405.02079 — ArgLLMs (2024/2025) | Formal AF on LLM output for explainability and contestability |
| ICSE 2026 / arXiv 2505.10961 — VulTrial | Courtroom-structured multi-agent code review; doubles single-agent accuracy |
| arXiv 2403.06294 — ArgMed-Agents (2024) | Typed argumentation moves (Walton schemes) in clinical multi-agent dialogue |
| arXiv 2306.14694 — Dialectical Reconciliation (KR 2024) | Formal grounding for the ratification/reconciliation step |
| arXiv 2511.07784 — Can LLM Agents Really Debate? (2024) | Identifies conformity collapse; motivates the CONCEDE/REVISE visibility design |
| arXiv 2603.01896 — Meta Agentic Code Reasoning (2026) | Structured "logical certificate" requirement before code review verdict |
| github.com/Alex-R-A/llm-argumentation-protocol | Practical adversarial protocol: evidence gates, challenge/defense phases |

---

*Qhorus — the LLM reasons; the infrastructure enforces, records, and derives.*  
*The normative layer made obligations accountable. The argument layer makes reasoning accountable.*  
*This document reflects the design discussion of 2026-05-16 and a systematic literature survey conducted the same day.*
