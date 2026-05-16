# Deliberation in the Software Development Lifecycle — Framework and Tiers

**Date:** 2026-05-16  
**Status:** Draft for discussion  
**Context:** Where deliberation sits in the SDLC, how it differs from review, and the three
infrastructure tiers that support it — from zero-overhead vocabulary-in-text to full
governance-grade Qhorus

---

## Three Parts of the SDLC

The design work across these specs has surfaced three distinct parts of the software
development lifecycle, each with different concerns and different tooling:

### 1. Triage

*Which work deserves attention?*

The trust model component. Too much work arrives — PRs from AI agents, contributors of varying
quality, domains the human has limited time to assess. The trust score on each contributor
(human or agent) filters the noise and routes high-signal work to the right reviewers. The
human's attention is the scarce resource; triage protects it.

**This is not about the work itself.** It is about prioritising where human attention goes.
DevTown's trust model (Bayesian Beta + EigenTrust, REVIEW_THOROUGHNESS, FALSE_POSITIVE_RATE,
SCOPE_CALIBRATION, RoutingPolicy with borderlineMargin) is the designed answer to this part.
See DevTown issue #24.

### 2. Review

*Is this work correct?*

Assessment of a finished or near-finished artifact — code, design, PR, architecture decision.
A reviewer (human or agent) evaluates what has been produced, flags issues, approves or requests
changes. GasTown's core workflow, extended by DevTown with AI reviewers and trust-weighted
routing.

The output is a verdict: this passes, or it does not, with specific issues attached. Trust
scores update from the outcome. The record is: what was reviewed, by whom, and what was decided.

**Review is assessment of a finished artifact.**

### 3. Deliberation

*What should we build, and how?*

Structured argument during the creation of work — positions taken, assumptions challenged,
positions revised. The argument vocabulary (CLAIM, PRESUME, REBUT, UNDERCUT, CONCEDE, REVISE)
is the designed answer to this part.

Deliberation happens primarily before or during implementation: two Claudes argue through a
design decision; a human challenges an agent's approach; a critique Claude pushes back on
assumptions until the strongest position is found. It can also happen inside review — a reviewer
challenges the author, the author argues back, they reach consensus on what to change.

**Deliberation is argument during the creation or critique of work, not assessment of it.**

---

## The Critical Distinction: Review vs. Deliberation

These are genuinely different:

| | Review | Deliberation |
|---|---|---|
| **Object** | Finished or near-finished artifact | Position, design, or approach |
| **Move types** | Approve / Flag / Request change | CLAIM / REBUT / UNDERCUT / CONCEDE / REVISE |
| **Goal** | Verdict on quality | Convergence on best position |
| **Human role** | Reviewer or oversight of reviewer | Overseer watching argument graph |
| **Record** | What was reviewed and decided | Why the conclusion was reached |
| **Trust signal** | Outcome (approve/reject quality) | Argument quality (REVISE rate, scope calibration) |

They can occur in sequence — deliberation shapes what gets built, then review assesses what was
built. They can also nest: review triggers deliberation when the reviewer and author disagree, and
deliberation reaches a position that review then validates.

The SDLC flow with all three:

```
Deliberation (design/implementation)
      ↓
  Work produced
      ↓
Triage (which PRs deserve attention?)
      ↓
  Selected for review
      ↓
Review (is this work correct?)
      ↓  ← deliberation may occur here when reviewer and author disagree
  Verdict
```

---

## Two Operational Tracks

The deliberation layer operates at two levels with different overhead profiles. The choice is
not a phase or a milestone — it is a deployment decision made per context.

### Engineering Track

Helps produce better engineering decisions. No ledger, no provenance chain, no compliance
requirement. The human uses the vocabulary to structure conversations with Claude, launches
a critique Claude when needed, watches the argument state, and moves on. Nothing is formally
recorded.

The overhead is near-zero. The value is immediate: better-examined assumptions, faster
convergence on good positions, the human not having to do all the critical work themselves.

Most use of the deliberation layer will be on the engineering track.

### Governance Track

Adds accountability, ledger entries, provenance, and ratification as a formal act. Each argument
move (CLAIM, REBUT, UNDERCUT, CONCEDE, REVISE) creates a tamper-evident ledger record. The
ratified argument graph carries the same deontic weight as a signed DONE in the commitment layer.

This track is for contexts where the WHY of a decision has regulatory weight: financial system
architecture, security design, clinical workflow decisions, any decision that may later require
explanation to an auditor, regulator, or court.

The overhead is significant. The value is: a human-verifiable record of every assumption, every
challenge, every concession, and every revision that produced the outcome — across the full
normative layer stack.

---

## Three Infrastructure Tiers

The same vocabulary works at all three tiers. What differs is the infrastructure behind it.

### Tier 1 — Vocabulary Only

**What it is:** The seven moves (CLAIM, PRESUME, SUPPORT, REBUT, UNDERCUT, CONCEDE, REVISE) used
in normal conversation text. No tooling. No channel. No storage. No infrastructure of any kind.

**What you get:** Structured deliberation in any conversation, immediately. The argument structure
is implicit in how the text is written. A checkpoint summary can be produced on demand by either
participant. The human carries the graph state — or a scratch document does.

**The key insight:** The vocabulary IS the lightweight version. Nothing needs to be built to use
it. Phase 1 of the argument graphs implementation is this tier, and it is available now.

**Trade-off:** No automatic state tracking. No convergence detection. No persistent record. The
human must manage the argument state and remember what was agreed.

**When to use:** Any engineering conversation where better structure helps. Default choice for
most deliberation.

### Tier 2 — Lightweight Qhorus Channel

**What it is:** An ephemeral channel (`debate/<session-id>`) that two Claude instances can both
post to and read from. The argument graph is extracted automatically from the vocabulary in the
messages and shown as a live state view — not an audit record. The channel is discarded when the
session ends.

**The use case:** Working in a terminal with Claude, wanting to launch a second Claude for
critique, and not wanting to mix the two conversations into one large context. The channel
provides the routing — each Claude writes to the shared channel, both can see the full debate,
and the argument graph shows current state without requiring either to read the full transcript.

**What you get:**
- Two Claude contexts stay separate (no context mixing)
- Argument graph as a navigation aid, not an audit trail
- Consensus detection (no outstanding REBUTs/UNDERCUTs = converged)
- Human can watch state without reading every message

**What you do NOT get:**
- Ledger entries
- Attestation or provenance
- Persistence beyond the session
- Normative layer enforcement

**Minimum viable design for this tier:**

```
create_debate_channel(session_id) → ephemeral Channel with no persistence policy
post_to_debate(channel, message)  → standard MessageService.send(EVENT)
get_argument_state(channel)       → extract graph from channel messages, return live view
detect_convergence(channel)       → scan for open REBUTs/UNDERCUTs, return converged/open/stalled
close_debate(channel)             → delete channel and all messages
```

No ledger write. No CommitmentStore. No LedgerEntry. The channel is a conversation routing
mechanism, not a governance artefact.

**Trade-off:** No persistence after session end. Not suitable for contexts where accountability
is needed. Not promoted to the governance track without explicit action.

**When to use:** Terminal-based multi-Claude deliberation, engineering track debate skill,
any case where conversation routing adds value but governance overhead does not.

### Tier 3 — Full Qhorus (Governance Grade)

**What it is:** Complete normative layer integration. Each argument move creates a
`MessageLedgerEntry` with SHA-256 tamper evidence. Ratification is a formal deontic act —
a signed checkpoint in the commitment layer. The argument graph is a permanent artefact,
queryable via MCP tools, with full causal chain from first CLAIM to final ratified position.

**What you get:**
- Every argument move tamper-evidently recorded
- Ratification as a formal act with the weight of a signed DONE
- Full provenance: who argued what, when, why they changed
- Cross-deliberation references (ratified positions from prior graphs usable as SUPPORT)
- DevTown trust signal integration (REVISE/CONCEDE feed trust dimensions)
- Human-verifiable record at any future point

**What you do NOT get for free:**
- Any of this is simple — the ledger write, the ratification lifecycle, the graph persistence
  all require Phase 2 implementation from the argument-graphs spec

**When to use:** High-stakes architectural decisions, security design, compliance-sensitive
contexts, any decision the human may need to explain to an auditor or regulator.

---

## Retroactive Promotion

A tier 1 conversation (vocabulary only, no infrastructure) can be retroactively promoted to a
tier 3 record if needed. The argument structure is already in the text — extraction is
pattern-matching on labelled moves, not semantic reconstruction.

The promotion path:
1. Tier 1 conversation uses vocabulary throughout
2. At any point: extract the argument graph from the conversation text (Phase 2 tooling)
3. Participants ratify the extracted graph (it accurately represents the conversation)
4. Ratification creates a ledger-grade record as of that point

This means the governance overhead does not need to be decided at the start of a deliberation.
A conversation that begins as engineering-track can be promoted to governance-grade at the moment
it becomes consequential — before the final decision, before implementation begins, before the
design is committed.

The vocabulary is the infrastructure-agnostic layer. The ledger is opt-in.

---

## How the Three SDLC Parts Map to Tools

| SDLC Part | Tool | What it does |
|---|---|---|
| **Triage** | DevTown trust model | Routes work by contributor trust; filters AI slop; protects human attention |
| **Review** | DevTown review workflow | Assesses finished artifacts; routes to reviewer by trust; records verdicts |
| **Deliberation (tier 1)** | Argument vocabulary in system prompt | Structures reasoning in conversation; zero overhead; available now |
| **Deliberation (tier 2)** | Lightweight Qhorus channel | Routes conversation between Claude instances; ephemeral; no ledger |
| **Deliberation (tier 3)** | Full Qhorus + normative layer | Governance-grade record; ledger; ratification; DevTown trust signals |

DevTown provides triage and review. The argument vocabulary (at any tier) provides deliberation.
The three are complementary: each addresses a different phase of the SDLC and a different
question about the work.

### Where DevTown and Deliberation Connect

DevTown's `HumanOversight` trigger is the join point. When trust is borderline and the system
cannot confidently route a decision automatically, the human is surfaced. Instead of the human
reviewing directly (HumanDecision), they launch a critique Claude (human-as-overseer). The
debate resolves the uncertainty. The argument graph gives the human enough signal to ratify.

At governance tier: the argument graph feeds trust signals back into DevTown's model (REVISE
moves signal REVIEW_THOROUGHNESS; CONCEDE moves on non-issues signal FALSE_POSITIVE_RATE).

At engineering tier: the debate happens, the human watches, the conversation ends. No trust
update. That is fine.

---

## Open Questions

**1. Tier 2 channel lifecycle management.** Ephemeral channels need to be garbage-collected when
sessions end. How does the lightweight Qhorus know when to clean up? Session termination hooks?
Explicit `close_debate` call? TTL?

**2. Promoting mid-deliberation.** If a tier 1 conversation decides at turn 15 to promote to
tier 3, what happens to the prior 15 turns? Are they retroactively ledgered (re-recorded from
the conversation text), or does tier 3 start from the promotion point only? The former produces
a complete record; the latter is simpler but creates a gap.

**3. Does tier 2 need a Qhorus instance at all?** The lightweight channel could be a much simpler
tool — a shared file, a local socket, a small in-memory pub/sub — if it only needs to route
messages between two processes and extract a graph. Qhorus is the right foundation for tier 3;
for tier 2 the question is whether it is right-sized or over-engineered.

**4. The engineering track and trust.** If a critique Claude consistently finds real issues on
the engineering track (its REBUTs cause REVISE), that signal is currently invisible to DevTown.
Should there be an opt-in mechanism to feed engineering-track argument signals into the trust
model? Or would this create incentive problems (engineering conversations conducted for trust
score reasons rather than engineering quality)?

---

*References: `docs/specs/2026-05-16-agent-argument-graphs.md` (vocabulary and governance
implementation), `docs/specs/2026-05-16-cross-repo-consensus.md` (multi-Claude deliberation
and debate skill), `docs/specs/2026-05-16-devtown-deliberation-fit.md` (DevTown integration),
DevTown issue #24 (contributor trust and routing)*
