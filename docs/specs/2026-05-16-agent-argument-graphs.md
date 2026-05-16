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
| Argument | CLAIM, PRESUME, REBUT, UNDERCUT… | What kind of *logical move* is being made | Reasoning trail, concession history, verdict |

Both layers follow the same principle: **the LLM reasons; the infrastructure records and derives**. Neither layer changes how LLMs think. Both make the structure of that thinking legible to the mesh.

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

*Why it matters:* UNDERCUT targets the *foundation* of an argument — the assumption or evidence that makes it work — rather than the conclusion itself. An agent that successfully UNDERCUTS a PRESUMPTION does not need to REBUT the CLAIM directly; if the foundation fails, the CLAIM built on it fails too. Confusing UNDERCUT with REBUT produces a weaker challenge and a murkier record.

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

This is pattern matching, not semantic inference. The vocabulary does the work.

When a human then says:

> "I UNDERCUT that PRESUMPTION — we agreed last week we can change anything for better architecture."

The graph gains:

```
UNDERCUT   → AttackEdge   { from: undercut-node, to: presume-node, type: UNDERCUT }
```

And when the agent responds:

> "I CONCEDE the PRESUMPTION. I REVISE my CLAIM to approach B..."

```
CONCEDE    → retract { argument: presume-node, reason: "presumption undercut" }
REVISE     → ArgumentNode { claim: "approach B is best", basis: INFERRED, inferred_from: [prior-claim] }
```

The vocabulary is the graph. Extraction formalises what is already there.

---

## Audit Checkpoints

The graph does not surface after every exchange. It surfaces at **checkpoint moments** — natural pause points where the argument structure has shifted or is about to produce a conclusion.

**Checkpoint triggers:**

- Before a final recommendation or decision is made
- When a PRESUME has been successfully UNDERCUT (a foundational assumption has changed)
- When an agent issues a REVISE (the position has materially shifted)
- When a new participant joins a deliberation in progress
- When any participant explicitly requests one

At a checkpoint, the agent produces a structured summary in the argument vocabulary:

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

This is human-readable, human-verifiable, and convertible to a graph without interpretation. It does not require new infrastructure to produce — the agent constructs it from its context window, using the vocabulary. The checkpoint is cheap to generate and cheap to review, because the participant was just in the conversation and can confirm or correct in seconds.

Checkpoints are not continuous annotation. They are structured moments of surface — like committing code, like signing minutes. The conversation flows naturally; the checkpoint makes the structure explicit at the moment it matters.

---

## Ratification

The checkpoint is a draft. Ratification is the act of agreeing that it accurately represents the deliberation.

Each participant — agent or human — either ratifies ("yes, this captures what I argued and conceded") or raises a correction ("I did not concede that; I said X only under assumption Y"). A correction is itself a graph operation: the agent or human uses the vocabulary to identify what is wrong and what the record should say instead. The corrected checkpoint is re-presented. Ratification is complete when all participants have confirmed.

This is the step that transforms the argument graph from a system-produced record into a **mutually agreed record**. Without ratification, the graph is a log. With it, it is a record that the participants confirmed is accurate — and that confirmation is itself logged, timestamped, and tamper-evident.

A ratified argument graph carries a different evidential weight than a reconstructed one. When accountability is demanded later — "why did you choose this architecture?", "what was the basis for this risk assessment?" — the answer is not "here is what the agents said" but "here is the record we all agreed is accurate, at the time."

---

## The Graph as Accountability Artefact

The argument graph answers seven questions that a pure message transcript cannot:

| Question | Without graph | With graph |
|---|---|---|
| Why did they conclude this? | Reconstruct from transcript | `get_argument_graph` — the reasoning trail |
| What was the dissenting position? | Read everything and infer | Unaccepted CLAIM nodes with their SUPPORT |
| What assumption was challenged? | Find the relevant exchange | UNDERCUT edges to PRESUME nodes |
| What caused the position to change? | Compare early and late messages | REVISE node linked to the UNDERCUT that caused it |
| What was conceded, and why? | Search for hedging language | CONCEDE nodes with stated reason |
| When did the balance shift? | Timeline reconstruction | Temporal graph — checkpoint history |
| Was the conclusion disputed at close? | Read final exchanges | Ratification status + any open REBUT edges |

For code review and design decisions these questions arise regularly in retrospect. For regulated decisions — architectural choices with long-term compliance implications, risk assessments, policy approvals — they are not optional. The argument graph makes them answerable without archaeology.

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

The layers are loosely coupled. A deliberation channel uses all three: messages carry the conversation, the commitment layer records who committed to the conclusion, and the argument graph records why that conclusion was reached. An operational channel — payment processing, status updates, sanctions screening — uses only the bottom two layers. The argument graph is opt-in for deliberative contexts. It does not touch channels where it adds no value.

Agents can reference argument nodes in message content via `arg:<uuid>` — the same pattern as `artefact_refs`. A message can say "I REVISE my earlier position; see `arg:7c2b`" and the channel gateway resolves the reference in read contexts. The layers stay in sync without either being derived from the other.

---

## Practical Workflow — Code Review

This is the canonical case. The workflow does not change for the participants:

1. You ask for a review of three architectural approaches.
2. Agent presents A, B, C using the argument vocabulary — CLAIMs for each, SUPPORTs with evidence, PREPSUMEs stated explicitly.
3. You push back: "why C? I already said we can change anything for better architecture."
4. Agent recognises this as an UNDERCUT of a PRESUME.
5. Agent CONCEDEs the PRESUME, REVISEs its position to B.
6. Before making the final recommendation, agent surfaces a checkpoint: *"Here is the argument as I understand it — does this represent your shared understanding?"*
7. You confirm or correct one point.
8. Agent ratifies and the graph is logged alongside the implementation decision.

Step 6 adds thirty seconds. The conversation in steps 1–5 is unchanged — natural language, back and forth, exactly as it has always been. The vocabulary is woven into how the agent writes, not bolted on as a separate activity.

The result: the decision has a tamper-evident reasoning trail that any future reviewer can read. Not "they chose B" — but "they chose B because the testability argument for A held, the change-constraint PRESUME was undercut, and C's async complexity was rebutted with incident evidence."

---

## Implementation Path

### Phase 1 — Vocabulary (zero new infrastructure)

Define the argument vocabulary formally: the seven moves, their definitions, what constitutes a good versus bad use of each, and worked examples. Introduce this into the system prompt for deliberation contexts — the same way the normative layer vocabulary is made available to agents via their channel context.

Run conversations using the vocabulary. Observe whether the structure comes through clearly in the text. This is the validation step — it tells you whether the vocabulary is right before any infrastructure is built. The normative layer was validated conceptually before the CommitmentStore existed. The argument layer follows the same path.

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

The explicit real-time tools (`assert_argument`, `attack_argument`, `retract_argument`) become available as optional precision instruments — useful when agents want to be explicit about their moves in structured multi-agent deliberation, but not required for the human-agent conversation case.

Deliverable: ArgumentNode, AttackEdge, SupportEdge entities; MCP tools above; ratification lifecycle; integration with normative ledger for tamper evidence.

### Phase 3 — Constrained logic engine (future, optional)

Add grounded semantics labelling (Dung, 1995) running over the stored graph. Labels (ACCEPTED / REJECTED / UNDECIDED) become computed rather than asserted. Add cycle detection, convergence detection, and `DELIBERATION_STALLED` watchdog integration.

This phase is not required for the accountability value. It adds formal guarantees for large multi-agent deliberations where the structure is complex enough that agents need computational help to navigate it. It does not change LLM reasoning in any way.

---

## Open Questions

**1. Vocabulary precision vs. naturalness.** The seven moves need to be precise enough to produce clean extraction but natural enough that agents use them without the vocabulary feeling forced. Getting this right requires iteration on real conversations — Phase 1 is the experiment.

**2. Cross-deliberation references.** Can a CLAIM in one deliberation reference an accepted position from a prior one ("the architectural principle ratified in argument-graph-47 applies here")? This enables cumulative organisational reasoning but requires a stable argument registry beyond individual channels. A v2 concern.

**3. Who can dispute a ratified graph?** Once ratified, the record is agreed. But new information may later show that a PRESUME was wrong in ways not visible at the time. Does the system allow a post-ratification dispute, and if so, who has standing to raise it? This is an authority and governance question, not a technical one.

**4. Weighting by trust score.** The existing normative layer derives EigenTrust scores from attestation history. A FLAGGED agent's CLAIM carries different evidential weight than an ENDORSED agent's. Should this weight appear in the graph — or does it distort the record by pre-judging arguments before they are evaluated on their merits? Probably a Phase 3 question.

---

*Qhorus — the LLM reasons; the infrastructure enforces, records, and derives.*  
*The normative layer made obligations accountable. The argument layer makes reasoning accountable.*  
*This document reflects the design discussion of 2026-05-16. It does not commit to an implementation schedule.*
