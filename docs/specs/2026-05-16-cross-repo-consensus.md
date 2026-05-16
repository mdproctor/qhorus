# Multi-Claude Deliberation — Design Exploration

**Date:** 2026-05-16  
**Status:** Draft for discussion  
**Context:** Launching a second Claude with a critique role, having it argue with the primary
Claude using the argument vocabulary, and reaching consensus — with the human as overseer
rather than reviewer

---

## The Pattern

The human currently does two jobs in any significant design or review session: the actual work
(which Claude handles) and the critique (which the human has to provide). The critique job is
the expensive one — spotting the flawed assumption, pushing from the security angle, asking what
happens under load, arguing for the simpler approach. These require sustained attention and
domain knowledge the human may not always have time or confidence to apply thoroughly.

The pattern this spec supports: instead of the human doing all the critique, launch a second
Claude with a specific role — critic, devil's advocate, security skeptic, simplicity advocate,
or any custom framing — and have it argue with the primary Claude. Both Claudes use the
argument vocabulary. The human oversees rather than reviews: watching the argument state,
intervening when needed, ratifying the consensus when it is reached.

This applies at several levels:
- **Within a single session / repository**: primary Claude proposes, critique Claude challenges
- **Across repositories**: Claude A detects work needed in repo B; Claude B does the work;
  Claude A reviews and challenges; both converge on the right outcome
- **Across projects**: the pattern is the same — a second Claude with a specific angle,
  structured argument, human in the loop

The cross-repo case is not a different problem. It is this pattern applied to the specific
context where the two Claudes happen to be working in different codebases.

---

## The Critique Roles

The human launches the second Claude with a role. The role determines what angle it argues
from. Examples:

| Role | What it does |
|---|---|
| **critic** | General flaw-finder — challenges assumptions, spots gaps, asks what could go wrong |
| **devil-advocate** | Argues against whatever the current position is, regardless of merit |
| **security** | Security-first angle — what are the attack surfaces, trust boundaries, data risks |
| **performance** | Load and latency angle — what breaks at scale, what allocates, what blocks |
| **simplicity** | Argues for the simpler approach — is this complexity justified, what can be removed |
| **alternative** | Argues for a specific named alternative — "argue the case for approach B" |
| **[custom]** | Any description — "argue as a sceptical CTO who has seen this fail before" |

The role is seeded into the critique Claude's context at launch. The critique Claude is not
neutral — it is specifically tasked to push back, find weakness, and not concede easily. Its
job is to make the primary Claude's position better by challenging it.

---

## What Consensus Means

Consensus is reached when both Claudes have no outstanding REBUTs or UNDERCUTs. The argument
graph labels this as converged: every CLAIM is either ACCEPTED (no successful challenge) or
superseded by a REVISED CLAIM that is itself accepted. The critique Claude has either
CONCEDEd all challenges or had them SUPPORTed by evidence it found sufficient.

Consensus does not mean the critique Claude agrees with everything. It means the argument
structure has resolved — every dispute has reached a terminal state. Open disagreements
surface as UNRESOLVED in the argument graph. The human decides whether to accept the
consensus with open items, resolve them directly, or run another round.

Consensus that required significant REVISE moves is better than consensus that required none
— it means the critique Claude found something real. The argument graph shows this: a clean
run with no REVISE moves is a signal that the critique role was too weak, not that the
primary Claude was right about everything.

---

## The Architecture

```
Human launches:
  /debate --role security "review this channel gateway design"

                    ┌─────────────────────────┐
                    │  Primary Claude         │
                    │  (current session)      │
                    │                         │
                    │  Holds the current      │
                    │  position / design      │
                    └──────────┬──────────────┘
                               │
                    Qhorus channel: debate/<session-id>
                               │
                    ┌──────────▼──────────────┐
                    │  Critique Claude        │
                    │  role: security         │
                    │                         │
                    │  Seeded with current    │
                    │  position + role brief  │
                    └──────────┬──────────────┘
                               │
                    ┌──────────▼──────────────┐
                    │  Claudony panel         │
                    │                         │
                    │  Argument graph state   │
                    │  Agreed / Disputed      │
                    │  Human approve/redirect │
                    └─────────────────────────┘
```

### Launch

A skill or Claudony command launches the critique Claude. At minimum it needs:

1. **The current position** — the primary Claude's proposal, design, or output, packaged as a
   structured brief: what is being argued, what constraints apply, what the primary Claude
   considers load-bearing
2. **The role brief** — what angle the critique Claude should argue from, how hard it should
   push (configurable: gentle challenge vs. adversarial)
3. **The shared channel** — a Qhorus `debate/<session-id>` channel both Claudes post to

The critique Claude is not a blank slate — it is seeded with enough context to argue
intelligently. The quality of the seeding determines the quality of the critique. A thin brief
produces a shallow critique; a brief that includes the primary Claude's own PRESUMEs (its
stated assumptions) gives the critique Claude the highest-value targets immediately.

### The Argument Loop

Both Claudes post to the shared channel using the argument vocabulary. The loop is:

1. Primary Claude posts its position as a CLAIM with SUPPORT and explicit PRESUMEs
2. Critique Claude reads and posts its challenges: REBUTs and UNDERCUTs
3. Primary Claude responds: defends (adds SUPPORT), CONCEDEs, or REVISEs
4. Critique Claude assesses the response: accepts (no further challenge) or continues
5. Repeat until converged or human intervenes

The argument graph is updated after each exchange. The human sees the current state:
what is accepted, what is still disputed, what has been revised.

### The Human's Role

The human does not read every message. They watch the argument graph state. When it shows:
- **All accepted**: consensus reached — read the final position and ratify
- **Some disputed**: read the specific disputed points and decide whether to let the argument
  continue or intervene with a clarification
- **Stalled**: the two Claudes are going in circles — intervene with a constraint or decision

The human can also set the stopping condition at launch: "stop after 3 rounds", "stop when
no new REBUTs appear", "run until I manually stop it". The last option is for exploratory
critique where the goal is to find problems, not necessarily to reach consensus.

---

## What This Replaces

The human currently:
- Reads the primary Claude's output
- Thinks of all the angles it might have missed
- Asks follow-up questions one by one
- Evaluates whether the responses are satisfactory
- Decides when it's good enough

The critique Claude does this job — more thoroughly, from a specific angle, without fatigue,
and in parallel with the primary Claude defending its position. The human's job becomes:
choosing the right role at launch, watching the argument graph, and making the final call.

The human is still essential — they choose the critique angle, they judge whether the
consensus is actually good enough, and they intervene when the argument goes in the wrong
direction. But they are no longer the sole source of critical pressure.

---

## The Argument Vocabulary in This Context

The vocabulary is what makes this tractable. Without it:
- The critique Claude produces free-text objections
- The primary Claude produces free-text responses
- The human has to read everything to understand the current state
- There is no structured record of what changed and why

With it:
- Every objection is a REBUT or UNDERCUT targeting a specific CLAIM or PRESUME
- Every response is a SUPPORT (defending), CONCEDE (withdrawing), or REVISE (updating)
- The argument graph tracks the state automatically
- The human reads the graph, not the transcript

The PRESUME seeding at launch is particularly important. If the primary Claude explicitly
states its assumptions as PRESUMEs in its initial CLAIM, the critique Claude can target them
directly with UNDERCUTs rather than having to infer what the assumptions are. This produces
faster, more precise critique — and surfaces the highest-value disagreements first.

---

## The Skill

A `debate` skill (or Claudony command) handles the launch. Invocation:

```
/debate --role <role> [--rounds N] [--intensity gentle|standard|adversarial] "<topic>"
```

What the skill does:

1. Reads the current session to understand the primary Claude's current position
2. Packages the position as a structured brief: CLAIM + SUPPORT + explicit PRESUMEs extracted
   from the conversation
3. Launches a new Claude instance (via Claudony) seeded with the role brief and the position
4. Opens a `debate/<session-id>` Qhorus channel
5. Posts the packaged position to the channel as the opening CLAIM
6. Returns a link to the Claudony argument graph panel
7. Runs the argument loop (N rounds or until converged)
8. At the end: shows the argument graph state and asks the human to ratify or continue

The skill works within a single repository session — no cross-repo coordination needed. Cross-
repo is just a variant where the primary and critique Claudes happen to be working in different
codebases, and the channel routes between them.

---

## Phases

**Phase 0 — Manual with vocabulary (now)**

The human launches a second Claude manually and seeds it with the primary Claude's position
using the argument vocabulary. "You are a security critic. Here is the current position:
CLAIM [X]. PRESUME [Y]. PRESUME [Z]. Your job is to REBUT or UNDERCUT." No tooling. The
human carries the argument graph state in their head or in a scratch document.

**Phase 1 — Shared Qhorus channel**

Both Claude instances post to a shared Qhorus channel. The argument graph is extracted
automatically at each checkpoint. The human sees the graph state without reading every message.

**Phase 2 — Debate skill with structured launch**

The `/debate` skill packages the position brief automatically, launches the critique Claude
with the right seeding, and manages the channel. The human sets the role and stopping condition
at launch and then watches the panel.

**Phase 3 — Claudony argument panel**

Full UI: argument graph state in real time, human approve/redirect actions, history of all
rounds, ratification flow.

---

## Open Questions

**1. How hard should the critique Claude push?** The `--intensity` parameter covers this
roughly, but the right level of adversarial pressure depends on context. A design in early
exploration needs gentle challenge; a design about to be implemented needs adversarial pressure.
Should the primary Claude be able to request a harder critique if the first round was too easy?

**2. How is the critique Claude's context bounded?** It needs enough context to argue
intelligently but not so much that it spends its context on irrelevant background. The position
brief needs to be concise and targeted. What format produces the best critique?

**3. Can the critique Claude change its role mid-argument?** A security critic might discover
a performance issue while arguing security. Should it be able to surface this, or does strict
role discipline produce better-focused critique?

**4. What happens when both Claudes are wrong?** The argument vocabulary tracks agreement, not
correctness. Two Claudes can reach consensus on a bad position. The human ratification step is
the correctness gate — but only if the human is paying enough attention to catch it. This is a
fundamental limitation of the approach.

---

*Built on: Qhorus (channel + argument graph) · Claudony (launch + panel) · CaseHub (case
tracking for cross-repo variant)*  
*The cross-repo case is a deployment variant of this pattern, not a separate design.*  
*References: `docs/specs/2026-05-16-agent-argument-graphs.md`*
