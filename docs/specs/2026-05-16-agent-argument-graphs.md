# Agent Argument Graphs — Design Exploration

**Date:** 2026-05-16  
**Status:** Draft for discussion  
**Context:** Qhorus multi-agent deliberation — structured argumentation between agents toward a shared decision

---

## The Problem This Solves

Qhorus already handles the *commitment* layer of multi-agent interaction: who promised what to whom, when, and what happened. What it does not yet handle is the *deliberative* layer — the structured back-and-forth that happens when agents are not executing instructions but are instead **reasoning together toward a decision**.

The canonical example: you ask four agents to review an architectural proposal. Each has a different frame — security, scalability, cost, maintainability. They will agree on some things and contest others. Eventually the system needs to produce a decision. Right now that deliberation is entirely invisible. Messages flow, arguments appear in message content, but the logical structure — which claim attacked which, which was conceded, what the remaining contested points are — is nowhere in the infrastructure. A human reading the transcript has to reconstruct it. A subsequent agent joining the thread has no structural anchor for the existing arguments. And when the decision is made, there is no record of *why* — only *that*.

An argument graph fixes this. It makes the logical structure of deliberation a first-class artefact that agents maintain, reference, and eventually converge on — independently of how the LLMs reason internally.

---

## What an Argument Graph Is

The foundation is **Dung's Abstract Argumentation Framework** (1995), extended with support edges and temporal metadata. The graph has three primitives:

```
ARGUMENT — a node
  id:         UUID (stable reference)
  claim:      free text (the proposition being argued)
  author:     agent instance id
  basis:      ASSERTED | INFERRED | EVIDENCE_BACKED | PRESUMPTION | DEFAULT
  created_at: timestamp
  retracted:  boolean (author withdrew it)
  label:      ACCEPTED | REJECTED | UNDECIDED (computed — see below)

ATTACK — a directed edge
  from:       argument id
  to:         argument id
  type:       REBUTTAL | UNDERCUT | UNDERCUTTER
  author:     agent instance id
  created_at: timestamp

SUPPORT — a directed edge
  from:       argument id
  to:         argument id
  type:       BACKING | CONVERGENT | LINKED
  author:     agent instance id
  created_at: timestamp
```

Three **attack types** matter because they mean different things:

| Type | What it does |
|------|-------------|
| **REBUTTAL** | Directly contradicts the claim ("it is not scalable" vs "it is scalable") |
| **UNDERCUT** | Disputes the basis ("your evidence is stale — that benchmark is from 2023") |
| **UNDERCUTTER** | Defeats the inferential link ("even if P and Q, R does not follow in this context") |

These distinctions are load-bearing in deliberation. An agent that UNDERCUTS is not saying the claim is wrong — it is saying the evidence for it is unreliable. That is a fundamentally different move, and collapsing them into a single "attacks" edge loses information that matters both for reasoning and for human review.

Three **argument bases** also carry distinct weight:

| Basis | Meaning |
|-------|---------|
| **ASSERTED** | The agent claims it on authority — "in my domain, this is standard" |
| **INFERRED** | Derived from other arguments in the graph — traceable |
| **EVIDENCE_BACKED** | References an artefact (artefact_ref) containing the evidence |
| **PRESUMPTION** | Holds unless explicitly challenged (shifted burden of proof) |
| **DEFAULT** | Holds in the absence of specific information — defeasible |

DEFAULT and PRESUMPTION are how defeasible logic enters without requiring a formal logic engine. A DEFAULT argument is automatically REJECTED if any REBUTTAL with higher basis reaches it. A PRESUMPTION flips the burden: the challenger must produce a REBUTTAL, not merely assert doubt.

---

## Temporal Structure

Deliberation is not a static graph — it unfolds over time. The graph is **append-only**: arguments and edges are never deleted, only retracted. Retraction is a first-class act (an agent explicitly withdraws a position, with a timestamp and optional reason). This matters because:

1. The history of how the deliberation unfolded is itself evidence of quality
2. An argument retracted under pressure is different from one retracted after new evidence
3. Human reviewers need to see when positions shifted, not just where they ended up

The temporal structure also enables a specific kind of query: *at what point did the balance of the argument shift, and what caused it?* This is particularly useful for post-decision review in regulated contexts.

---

## How Agents Interact With the Graph

Agents use MCP tools to write to and read from the graph. The tools are deliberately minimal — agents are not asked to perform logic, only to record their logical moves.

**Writing:**

```
assert_argument(
  claim:           string,          -- the proposition
  basis:           ArgumentBasis,   -- ASSERTED | INFERRED | EVIDENCE_BACKED | PRESUMPTION | DEFAULT
  channel_name:    string,          -- scopes the graph to a deliberation
  artefact_refs:   string[],        -- optional — evidence artefacts from DataService
  inferred_from:   string[]         -- argument ids this is derived from (if INFERRED)
) → argument_id

attack_argument(
  argument_id:     string,          -- what is being attacked
  claim:           string,          -- why
  attack_type:     AttackType,      -- REBUTTAL | UNDERCUT | UNDERCUTTER
  basis:           ArgumentBasis
) → argument_id                     -- the new counter-argument's id

support_argument(
  argument_id:     string,
  claim:           string,
  support_type:    SupportType,     -- BACKING | CONVERGENT | LINKED
  artefact_refs:   string[]
) → argument_id

retract_argument(
  argument_id:     string,
  reason:          string           -- why the agent withdrew the position
)

concede_to(
  argument_id:     string,          -- the argument the agent is conceding to
  note:            string           -- optional acknowledgment
)
```

**Reading:**

```
get_argument_graph(
  channel_name:    string,
  as_of:           timestamp        -- optional — point-in-time snapshot
) → full graph with computed labels

get_argument(
  argument_id:     string
) → node + incoming and outgoing edges

get_contested_arguments(
  channel_name:    string
) → arguments currently labelled UNDECIDED

get_consensus_state(
  channel_name:    string
) → { accepted: [...], rejected: [...], undecided: [...], converged: boolean }

get_argument_history(
  argument_id:     string
) → full event log: assertions, attacks, retractions, concessions, label changes
```

Crucially, agents can **reference argument ids in their messages**. A message's `content` field can include `arg:<uuid>` references, and the channel gateway resolves these to argument summaries in read contexts — the same pattern as `artefact_refs`. This means an agent's message can say "I concede `arg:3f4a` but maintain `arg:7c2b`" and the infrastructure knows what those are, even if the LLM's prose also says it in natural language. The graph and the message stream stay in sync without requiring one to be derived from the other.

---

## Graph Representation Alone vs. a Logic Engine

This is the central question. The honest answer is: **start with graph representation, add a constrained logic engine for labelling and termination detection — but never let the engine override agent reasoning**.

Here is the breakdown:

### What the graph alone gives you

- A shared, persistent, queryable record of every logical move
- Human reviewability — the graph renders as a structured debate tree, not a transcript
- Agent referenceability — `arg:<id>` anchors in messages
- History — who argued what, in what order, with what retractions
- No change to LLM reasoning whatsoever

This is substantial. For most deliberation use cases — code review, approach selection, risk assessment — the graph alone may be sufficient. Agents read the graph, reason in natural language, write their logical moves back, and the human gets a structured record.

### What the graph alone cannot do well

**Labelling arguments without a rule.** Without a labelling algorithm, who decides if an argument is ACCEPTED or REJECTED? If agents label their own arguments, the labels are biased. If labelling requires consensus, what constitutes it? Without a rule, ACCEPTED is just a tag with no agreed semantics.

**Detecting cycles.** Defeasible reasoning allows A attacks B attacks C attacks A — a dialectical cycle. These are not pathological; they indicate genuine unresolved tension. But agents reading the raw graph cannot reliably detect when they are in a cycle vs. when one position genuinely defeats the other. The graph represents the structure; detecting cycles is structural computation, not reasoning.

**Termination.** When is deliberation over? "When all agents agree" is underspecified — agents may simply stop arguing without having resolved anything. A convergence condition requires a definition: what constitutes a stable state of the graph?

**Burden-of-proof shift.** A PRESUMPTION argument holds unless challenged. Who enforces this? If an agent asserts a PRESUMPTION and no one rebuts it, it should be ACCEPTED by default. But without a rule, agents may not know to challenge it, or may not realize they have conceded by silence.

### What a constrained logic engine gives you

A lightweight engine running over the graph — not over the agents' reasoning — can handle exactly these structural questions. The engine should implement **grounded semantics** (Dung, 1995): the unique, most skeptical stable labelling of the graph given the current set of arguments and attack edges.

The grounded semantics produce three labels for every argument node:
- **ACCEPTED** — in the grounded extension (defeats all attackers)
- **REJECTED** — attacked by an ACCEPTED argument
- **UNDECIDED** — in a cycle or unresolved tension; neither accepted nor rejected

These labels are computed, not asserted. Agents can see them but not directly set them. This is the key design constraint: **the engine produces the formal label; the agent expresses its position in the graph by choosing what to argue and what to concede, not by writing to a label field**.

The engine also provides:
- **Cycle detection** — identifies odd-length attack cycles (undecidable under grounded semantics) and surfaces them as deliberation blockers
- **Convergence detection** — the graph has converged when no argument is UNDECIDED; all positions are formally ACCEPTED or REJECTED
- **Burden enforcement** — a PRESUMPTION argument with no REBUTTAL edges is automatically ACCEPTED under grounded semantics, regardless of whether any agent explicitly conceded

This is minimal by design. The engine does not:
- Generate arguments
- Evaluate the content of claims
- Produce explanations
- Route the deliberation
- Override any agent's stated position

The LLMs reason. The engine labels. These are separate activities.

### The right mental model

Think of the engine as doing the same job as a judge applying rules of evidence and procedure — not deciding who is right, but maintaining the formal structure of the proceeding. The attorneys (agents) make their arguments. The judge (engine) applies the rules: this argument is in evidence, this one has been successfully challenged, these two are in genuine unresolved tension. The verdict (convergence) is reached when the formal structure resolves, not when the judge decides.

This maps directly to the existing Qhorus philosophy: **the LLM reasons; the infrastructure enforces, records, and derives**. The argument graph and its engine are the infrastructure for deliberative reasoning, in the same way the CommitmentStore and normative ledger are the infrastructure for obligation reasoning.

---

## Relationship to the Existing Normative Layer

The argument graph is a **layer above** the message and commitment layers — not a replacement for either.

```
+-----------------------------------------------------------------------+
|  ARGUMENT GRAPH LAYER                                                 |
|  "What was argued, by whom, against what, and what is currently      |
|   accepted — across the full deliberation"                            |
|                                                                       |
|  ArgumentNode (claim, basis, author, label)                          |
|  AttackEdge / SupportEdge                                            |
|  Grounded semantics engine (labelling, convergence detection)         |
+-----------------------------------------------------------------------+
                  ↕  argument_refs in message content
+-----------------------------------------------------------------------+
|  COMMITMENT / OBLIGATION LAYER (existing)                            |
|  "Who owes what to whom, resolved or stalled"                        |
|                                                                       |
|  CommitmentStore — 7-state lifecycle                                 |
|  MessageLedgerEntry — tamper-evident record                          |
+-----------------------------------------------------------------------+
                  ↕  speech acts create/resolve commitments
+-----------------------------------------------------------------------+
|  MESSAGE / SPEECH ACT LAYER (existing)                               |
|  "What was said: QUERY COMMAND RESPONSE STATUS DECLINE HANDOFF       |
|   DONE FAILURE EVENT"                                                 |
+-----------------------------------------------------------------------+
```

Messages remain the primary communication channel. The argument graph is a structured extract of the *logical content* of those messages — maintained by agents who explicitly make logical moves via tools, not automatically derived from message text. The two are loosely coupled: an agent that argues in a message but never calls `assert_argument` is using only the message layer. An agent that calls `assert_argument` always produces a message too (via the normal send path). The graph is additive, not required.

This is intentional. Deliberation channels use the argument graph; command-and-control channels (operational channels, payment processing, status updates) do not. The argument graph is opt-in infrastructure for cases where the logical structure of a decision matters to humans and to subsequent agents.

---

## Deliberation Lifecycle

A deliberation runs as follows:

1. **Open** — a channel is created with `semantic: DELIBERATION` (a new ChannelSemantic value) and a `motion` field: the proposition under deliberation ("accept this architectural approach", "approve this design"). The motion is automatically added as an ASSERTED argument by the system.

2. **Argue** — agents join the channel, read the current graph state, and make their logical moves via the argument tools. Each move corresponds to a message in the channel (the graph tool calls are transactional with the message send). Agents may argue in multiple rounds, reading the updated graph state between turns.

3. **Resolve** — the engine continuously recomputes labels. Convergence is reached when all arguments are ACCEPTED or REJECTED with none UNDECIDED. The motion's root argument is either ACCEPTED (consensus to proceed) or REJECTED (consensus to refuse).

4. **Deadlock** — if deliberation runs N rounds without convergence, a `DELIBERATION_STALLED` watchdog alert fires (the existing Watchdog mechanism). A human can then intervene via the oversight channel, or a designated tie-breaker agent can be assigned via COMMAND.

5. **Close** — the channel closes with a `DeliberationOutcome`: the final graph state, the motion verdict, the number of rounds, and which arguments remained UNDECIDED at close (if deadlock forced closure). The outcome is a ledger artefact — tamper-evident and queryable.

---

## Human Review

The graph was designed for human review as a primary use case, not an afterthought. A human reviewing a deliberation gets:

- **The argument tree** — visual or serialized graph of every claim, with attack and support edges labelled by type and author
- **The timeline** — which arguments appeared when, what retractions happened, and when the balance shifted
- **The undecided region** — which arguments remain in genuine tension and why (odd-length attack cycles surfaced explicitly)
- **The motion verdict** — the formal outcome with the chain of accepted arguments that produced it

This is structurally different from reading a transcript. A transcript requires the human to reconstruct the logical structure. The argument graph presents it directly. For regulated decisions — a risk assessment, a compliance ruling, an architectural choice with long-term consequences — this is the difference between "here is what they said" and "here is the structure of why they concluded what they did."

---

## What Stays Unchanged

To be explicit about the key constraint:

- LLMs continue to reason in natural language using their full context window
- LLMs continue to use all existing Qhorus message types
- The normative ledger continues to record everything tamper-evidently
- The commitment lifecycle is unchanged
- No LLM is asked to produce formal logic or structured output outside of argument tool calls

The argument graph tools are just structured write calls — the same kind of structured act as `send_message`. An agent calls `attack_argument(id, claim, REBUTTAL)` the same way it calls `send_message(channel, content, DECLINE)`. The LLM's reasoning produced that decision; the tool call makes it infrastructure-legible.

---

## Open Questions

1. **Semantic channel vs. argument graph per channel** — should every channel support an argument graph (opt-in), or should deliberation channels be a distinct channel type? The semantic approach (new ChannelSemantic) is cleaner for the data model. The opt-in approach is more flexible for emergent deliberation. Both are compatible with existing infrastructure.

2. **Who can retract?** — only the original author, or can the system retract an argument whose basis has been demonstrated to be false? The question of "objective retraction" raises authority questions that may be out of scope for v1.

3. **Weighted arguments** — some deliberation frameworks weight arguments by source credibility (EigenTrust score from the existing normative layer). A FLAGGED agent's argument carries less evidential weight than an ENDORSED agent's. Is this a v1 concern or something to introduce only when trust scoring is mature?

4. **Cross-deliberation argument references** — can an argument in one deliberation reference an accepted argument from a prior one? This enables cumulative organizational reasoning ("the architectural principle accepted in deliberation-47 applies here"). Requires a stable argument registry beyond individual channels.

5. **Grounded vs. preferred semantics** — grounded semantics are skeptical (the smallest stable labelling). Preferred semantics are credulous (the largest). For decisions with high stakes, grounded is safer — you only accept what is unambiguously defensible. For decisions where progress matters more than certainty, preferred semantics may be appropriate. Should this be a channel configuration parameter?

---

## Recommendation

Build the argument graph in two phases:

**Phase 1 — Graph only.** Implement ArgumentNode, AttackEdge, SupportEdge as entities. Add MCP tools: `assert_argument`, `attack_argument`, `support_argument`, `retract_argument`, `get_argument_graph`, `get_consensus_state`. Labels are manually set by agents (ACCEPTED/REJECTED/UNDECIDED as an explicit declaration, not computed). No engine. This gives the shared representation and human reviewability immediately.

**Phase 2 — Constrained engine.** Add the grounded semantics labelling algorithm running over the current graph state. Labels become computed (read-only for agents). Add convergence detection and `DELIBERATION_STALLED` watchdog integration. Add cycle detection output in `get_contested_arguments`. This closes the gap between "shared record" and "formally grounded deliberation".

Phase 1 is useful independently and produces no risk. Phase 2 adds formal guarantees without changing anything about how LLMs reason. The two phases can be delivered on separate issues.

---

*Qhorus — the LLM reasons; the infrastructure enforces, records, and derives.*  
*This document is the seed for a formal spec. It does not commit to an implementation schedule.*
