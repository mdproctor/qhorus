# Multi-Claude Deliberation — DevTown Fit Analysis

**Date:** 2026-05-16  
**Status:** Draft for discussion  
**Context:** How the multi-Claude deliberation pattern and argument vocabulary fit within DevTown's
existing review and trust model. Is this an extension of DevTown, a parallel track, or does DevTown
already provide the primitives this pattern needs?

---

## The Short Answer

The multi-Claude deliberation pattern is not a separate tool from DevTown — it is a natural
extension of DevTown's existing review workflow. DevTown already has:

- Review routing by trust score
- HumanOversight triggered when trust is borderline
- SCOPE_CALIBRATION derived from formal DECLINED speech acts
- Trust maturity phases that map directly to critique intensity levels

What the argument vocabulary adds is structured deliberation *inside* the review that DevTown
already routes. The vocabulary makes the review auditable: not just who reviewed it and what they
decided, but the argument trail — what assumptions drove the conclusion, where they were challenged,
and why the position changed or held.

The combination is: **DevTown routes the work and tracks the decision. The argument vocabulary
records why the decision is what it is.**

---

## What DevTown Already Has

### HumanOversight as the Debate Entry Point

DevTown's `HumanOversight` signal is triggered when an agent's trust score is within the
`borderlineMargin` of the routing threshold. This is the moment of maximum routing uncertainty:
the system cannot confidently route the decision automatically, and the human is surfaced.

This is exactly the point where `/debate` belongs. Rather than the human making the routing
call directly (human-as-reviewer), they launch a critique Claude against the primary Claude's
review (human-as-overseer). The debate resolves the uncertainty; the argument graph gives the
human enough signal to ratify the outcome.

```
Trust score → borderline margin → HumanOversight → human launches /debate → argument graph →
human ratifies → routing decision made
```

`HumanOversight` becomes the trigger condition for the debate skill, not a separate human
intervention path. The human does the same job either way; the debate skill makes the job
more systematic and less cognitively expensive.

### Trust Maturity Phases Map to Critique Intensity

DevTown defines four trust maturity phases. These map directly to the `--intensity` parameter:

| DevTown Phase | Behaviour | Debate Intensity |
|---|---|---|
| **Phase 0** — bootstrap | Trust data sparse; routing by availability | N/A — not enough signal yet |
| **Phase 1** — emerging | Trust visible; not yet used for routing | `--intensity gentle` — probe without adversarial pressure |
| **Phase 2** — active | Trust drives routing; borderline detection | `--intensity standard` — normal challenge level |
| **Phase 3** — adaptive | Per-capability trust floors | `--intensity adversarial` — high-stakes decisions; trust floors enforced |

At Phase 0, there is no basis for trust-weighted routing and debate results cannot yet feed back
into trust tracking. The skill is available but its outputs are not yet wired into the trust model.
At Phase 3, the trust system is mature enough to track critique effectiveness per role
(see below) and adjust routing accordingly.

### SCOPE_CALIBRATION and the CONCEDE Move

DevTown's `SCOPE_CALIBRATION` dimension measures whether a reviewer stays within their domain:
it is derived from formal `DECLINED` speech acts — an agent that consistently declines out-of-scope
work scores well on calibration.

The argument vocabulary's `CONCEDE` move is the deliberation-level analogue. An agent that
CONCEDEs challenges outside its domain (e.g., a security critic that concedes performance
arguments because it isn't the right angle for it) is demonstrating scope calibration inside a
debate. This is currently untapped: CONCEDE moves in the argument graph could feed directly into
the `SCOPE_CALIBRATION` trust dimension.

The mapping: DECLINED (speech act layer) = role-level scope enforcement;
CONCEDE (argument layer) = argument-level scope calibration. Both measure the same underlying
quality — does this agent know what it knows and stay within it?

### HumanDecision vs. HumanOversight Mirrors Reviewer vs. Overseer

DevTown distinguishes:
- `HumanDecision` — the human makes the call directly
- `HumanOversight` — the human approves/redirects an agent decision

The multi-Claude deliberation spec uses the same distinction:
- Human-as-reviewer (current state) — human reads primary Claude's output and forms their own critique
- Human-as-overseer (target state) — human launches critique Claude, watches argument graph, ratifies

The DevTown vocabulary already has the right labels. The debate skill operationalises the
transition from `HumanDecision` to `HumanOversight` for the design and review workflow.

---

## What the Argument Vocabulary Adds to DevTown

### The Gap: Routing Tracks Decisions; It Does Not Track Why

DevTown's normative ledger records every speech act — COMMAND, DECLINE, DONE, FAILURE. The
trust model learns from outcomes. What it does not track is the reasoning inside the decision:
why the reviewer flagged this concern, what assumption drove the rejection, what counter-argument
changed the position.

The argument graph fills this gap. The argument graph IS the review audit trail. It records:
- The CLAIM the primary Claude argued
- The PRESUMEs that drove it (stated and implicit)
- The REBUTs and UNDERCUTs the critique Claude raised
- Which challenges held (led to REVISE) and which were CONCEDEd
- The final position and the path it took to get there

This is the explainability layer the trust model currently lacks. The trust score tells you
*how reliable* the agent is. The argument graph tells you *why this specific decision* was
reached. Together: accountability at the decision level, not just the statistical level.

### REVISE Moves as Trust Signals

A critique Claude whose REBUTs and UNDERCUTs cause the primary Claude to REVISE is finding real
problems. A critique Claude whose challenges are all CONCEDEd by the primary did not find anything
real — or is operating outside its domain.

This maps directly to DevTown's existing trust dimensions:

| Argument graph signal | Trust dimension | What it measures |
|---|---|---|
| REBUT/UNDERCUT → primary REVISE | `REVIEW_THOROUGHNESS` | Critique found real issues |
| REBUT/UNDERCUT → primary CONCEDE (challenge rejected) | `FALSE_POSITIVE_RATE` | Critique flagged non-issues |
| Critique CONCEDEs out-of-domain | `SCOPE_CALIBRATION` | Critique stayed in its lane |

The argument graph gives DevTown's trust model three new data points per debate round,
grounded in the structure of the deliberation rather than binary outcomes.

### The PRESUME + UNDERCUT Pair Surfaces Assumptions Before They Ship

DevTown's review workflow finds problems in code or design. The argument vocabulary finds
problems in the *reasoning* behind code or design — specifically, the hidden assumptions
(implicit PRESUMEs) that the primary Claude did not know it was making.

These are the hardest failures to catch in post-review because the assumption was never stated.
By making PRESUMEs explicit at the start of the debate, the critique Claude can target them
directly. The argument graph records which assumptions were challenged and whether they held.

This is upstream-of-DevTown problem detection: it finds reasoning failures before they become
implementation failures.

---

## The Combined Architecture

```
PR or design submitted to DevTown
           │
           ▼
    Trust routing
    ┌────────────────────────────────────────┐
    │ Trust score above threshold?           │
    │   Yes → route to review agent (auto)   │
    │   No  → HumanOversight triggered       │
    └─────────────────────┬──────────────────┘
                          │ HumanOversight
                          ▼
               Human launches /debate
               --role <appropriate>
               --intensity <phase-mapped>
                          │
              ┌───────────┴──────────────┐
              │  Primary Claude          │
              │  posts CLAIM + PRESUMEs  │
              └───────────┬──────────────┘
                          │  debate/<session-id> channel
              ┌───────────▼──────────────┐
              │  Critique Claude         │
              │  REBUTs + UNDERCUTs      │
              └───────────┬──────────────┘
                          │
              ┌───────────▼──────────────┐
              │  Argument graph state    │
              │  (Claudony panel)        │
              └───────────┬──────────────┘
                          │
               Human ratifies or redirects
                          │
              ┌───────────▼──────────────┐
              │  DevTown trust update    │
              │  REVISE → thoroughness   │
              │  CONCEDE → false-pos-rate│
              │  Scope CONCEDE → calib.  │
              └──────────────────────────┘
```

---

## Issue #24 — Contributor Trust for PR Routing

Issue #24 defines Bayesian Beta + EigenTrust for contributor trust scoring, bootstrapped from
PR history and augmented by vouching. This applies to human contributors and AI agents alike.

For critique Claudes, the same model applies — but the trust object is the critique *role*,
not the individual instance:

- A `security` critic that consistently finds real issues (its REBUTs → REVISE) earns a high
  thoroughness score for security reviews
- A `simplicity` critic that keeps flagging things outside its remit (its REBUTs → primary
  CONCEDE) earns a high false-positive rate
- Over time: the system can route borderline security decisions to debate with `--role security`
  automatically, because the security critic role has demonstrated effectiveness

This is the Phase 3 adaptive trust extension of the debate skill. Phase 0–2 the trust tracking
is manual or absent; Phase 3 the routing policy knows which critique roles are effective in
which domains and uses that to decide when to trigger debate automatically.

---

## Where the Debate Skill Belongs

The debate skill is not a standalone tool. It belongs inside DevTown's review workflow as the
structured deliberation step that `HumanOversight` triggers. Concretely:

1. DevTown routes review work to agents by trust score
2. When trust is borderline, HumanOversight is triggered
3. The human uses `/debate` instead of reviewing manually
4. The argument graph feeds trust signals back into DevTown's model

The skill is a Claudony-level capability (launching the critique Claude, managing the channel)
that integrates with DevTown's routing and trust tracking. It does not need to be reimplemented
inside DevTown — it needs hooks that DevTown can call: `on_human_oversight_triggered(context)`.

---

## Open Questions from the DevTown Angle

**1. How does the argument graph get recorded in the normative ledger?**  
Each CLAIM, REBUT, UNDERCUT, CONCEDE, REVISE is a logical act in the debate channel. These
should become MESSAGE events in the ledger — but they are not speech acts in the normative
sense. Are they a new ledger entry type, or do they live as structured content inside EVENT
messages?

**2. Who is the trust subject for a critique Claude?**  
The instance (ephemeral, destroyed after the debate) or the role (durable, accumulates across
debates)? Issue #24's vouching model assumes durable identities. The critique Claude is by
definition ephemeral. The role-as-trust-subject is the more natural choice, but it needs a
new trust entity type in DevTown.

**3. Is SCOPE_CALIBRATION the right dimension for CONCEDE moves?**  
SCOPE_CALIBRATION in DevTown is currently about staying within declared domain. Argument-level
CONCEDEs are finer-grained — they reflect the quality of individual challenges, not domain
boundaries. The dimensions might need a fourth: `ARGUMENT_QUALITY` or similar.

**4. Should DevTown trigger debate automatically at Phase 3?**  
At Phase 3, routing is adaptive per capability. If the trust model knows that a security critic
role is highly effective, should DevTown automatically trigger a security debate for any PR
that touches trust boundaries — without human initiation? This is an escalation of human
oversight into autonomous deliberation, which is a significant design choice.

---

*Analysis references: `docs/specs/2026-05-16-agent-argument-graphs.md` (vocabulary),
`docs/specs/2026-05-16-cross-repo-consensus.md` (multi-Claude deliberation),
DevTown issue #24 (contributor trust), DevTown `docs/gastown-casehub-analysis-v2.md`*
