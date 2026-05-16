# argument-sweep

Sweep a deliberation — a conversation where positions were argued, challenged, and refined — and
produce two things: a reconstructed argument graph using the Qhorus argument vocabulary, and a
case study entry recording how well the vocabulary fit the actual conversation.

This skill is the feedback loop for the argument vocabulary itself. Run it after any significant
deliberation (design discussion, code review, approach selection) to see whether the seven-move
taxonomy mapped cleanly onto what happened — and to accumulate evidence about where the
vocabulary works, where it is too coarse, and where it misses a move type entirely.

---

## When to invoke

```
/argument-sweep                         — sweep the current session's deliberation
/argument-sweep <topic>                 — sweep the most recent deliberation on a topic
/argument-sweep <channel_name>          — sweep a specific Qhorus channel's history
```

---

## The Argument Vocabulary (reference)

Seven moves cover deliberative conversation. The skill reconstructs every deliberation using
exactly these labels — no others.

| Move | What it captures |
|---|---|
| **CLAIM** | A position asserted with stated reasoning |
| **PRESUME** | An assumption the argument depends on — stated or implicit |
| **SUPPORT** | Evidence or reasoning backing a CLAIM |
| **REBUT** | Direct contradiction of a specific CLAIM |
| **UNDERCUT** | Challenge to the assumption or evidence behind a CLAIM, not the CLAIM itself |
| **CONCEDE** | Explicit or implicit withdrawal of a prior position |
| **REVISE** | An updated CLAIM in light of a REBUT or UNDERCUT |

The vocabulary is defined in full with good/bad examples in:
`docs/specs/2026-05-16-agent-argument-graphs.md`

---

## What to do, step by step

### Phase 1 — Locate the deliberation

If a channel name or topic was provided as an argument, target that conversation. Otherwise use
the current session.

Read the full conversation. Identify the **deliberative segments** — stretches where positions
were taken, challenged, or updated, as distinct from purely informational or operational
exchanges. A deliberative segment has at least one position and at least one challenge or
concession.

If there are multiple deliberative segments, sweep each one separately.

### Phase 2 — Extract argument moves

For each deliberative segment, read through and identify every logical move. Classify each as
one of the seven move types. Do not force a move into a category that does not fit — note it as
**UNCLASSIFIED** if it genuinely does not map.

For each move record:

```
Move type:    CLAIM | PRESUME | SUPPORT | REBUT | UNDERCUT | CONCEDE | REVISE | UNCLASSIFIED
Author:       agent name, human, or system
Content:      the substance of the move in one sentence
Explicit:     YES — the author used the vocabulary word
              NO  — the move was implicit; the label is the reconstructed classification
Targets:      which prior move this attacks, supports, or updates (if applicable)
```

Pay particular attention to:
- **Implicit PRESUMEs** — assumptions the argument depended on that were never flagged as
  assumptions. These are the highest-value find: they are the hidden hinges that, if surfaced
  explicitly, would have invited earlier challenge.
- **Implicit CONCEDEs** — positions quietly dropped without acknowledgement. These leave no
  record of what changed the author's mind.
- **REBUT vs UNDERCUT confusion** — was the challenge targeting the conclusion or the
  foundation? The distinction matters for the record.

### Phase 3 — Reconstruct the argument graph

Produce a checkpoint-style summary of the deliberation using the vocabulary. Format:

```
Argument graph — [topic]:

CLAIM [author]: [position]
  SUPPORT: [evidence/reasoning]
  PRESUME [author]: [assumption — mark as (implicit) if it was not stated]
    ← UNDERCUT [author]: [why the assumption failed]
CONCEDE [author]: [what was withdrawn and why — mark as (implicit) if silent]
REVISED CLAIM [author]: [updated position]
  SUPPORT: [new reasoning]

Standing at close:
  Accepted (uncontested):    [positions that were not successfully challenged]
  Rejected (conceded):       [positions that were withdrawn]
  Revised:                   [positions that were updated]
  Unresolved:                [positions where challenge and defence remain open]
```

### Phase 4 — Fit assessment

Score the deliberation on three dimensions:

**Vocabulary coverage** — what fraction of moves mapped cleanly to one of the seven types?
- Clean (90–100%): the vocabulary covered everything
- Mostly clean (70–89%): one or two moves required an UNCLASSIFIED label
- Partial (50–69%): a significant minority of moves did not fit
- Poor (< 50%): the vocabulary missed a major move type present in this conversation

**Explicit vs implicit** — what fraction of moves were already using the vocabulary explicitly?
- This is the measure of vocabulary adoption, not vocabulary fit.
- A deliberation with 0% explicit and 100% reconstructable is a good argument for the
  vocabulary being the right one — the structure was there, just unlabelled.
- A deliberation with 0% explicit and significant UNCLASSIFIED content suggests the vocabulary
  needs extension.

**Key finding** — one sentence: what did this sweep reveal that is most useful for vocabulary
development? For example:
- "PRESUME was the move most often implicit — it is the highest-value move to emphasise in the
  system prompt"
- "A REBUT/UNDERCUT confusion appeared twice — the distinction needs a clearer worked example"
- "An UNCLASSIFIED move appeared: clarifying a scope boundary before arguing within it — this
  may need a SCOPE-LIMIT move type"

### Phase 5 — Case study diary entry

Write a diary entry capturing this deliberation as a case. Use the technical blog writing style
from `~/claude-workspace/writing-styles/blog-technical.md` if available. Otherwise: first
person or "we", past tense for what happened, present tense for conclusions, no filler phrases,
concrete and specific.

The entry should cover:

1. **What the deliberation was about** — one sentence
2. **How it unfolded** — the narrative arc: what the starting positions were, what challenged
   them, what changed
3. **The argument structure** — include the reconstructed graph (from Phase 3) inline
4. **What the vocabulary did well** — where explicit or reconstructable labelling clarified the
   structure
5. **What the vocabulary missed** — UNCLASSIFIED moves, implicit moves that should have been
   explicit, move types that felt forced
6. **What it suggests for the spec** — one or two concrete refinements

Keep the entry to 400–600 words. It is a case study, not a paper.

### Phase 6 — Vocabulary feedback (if warranted)

If the sweep produced UNCLASSIFIED moves, or if a pattern of implicit moves suggests a systemic
gap, produce a brief feedback note:

```
Vocabulary feedback from [date] sweep:

UNCLASSIFIED moves found: [count]
  Description: [what these moves were doing that the vocabulary didn't cover]
  Candidate move type: [proposed label, if any]

Implicit move pattern: [which move type was most often implicit]
  Suggested system prompt emphasis: [what to add or strengthen]

Move type confusion: [which pairs were hard to distinguish in practice]
  Suggested clarification: [what to add to the worked examples]
```

If no UNCLASSIFIED moves and the implicit pattern is unremarkable, skip this phase.

### Phase 7 — Save outputs

**Case study entry:** Save to `blog/YYYY-MM-DD-argument-sweep-<topic>.md` in the workspace.
If the workspace path is not available in this environment, write the entry to
`docs/case-studies/YYYY-MM-DD-argument-sweep-<topic>.md` in the project repo.

**Vocabulary feedback:** If Phase 6 produced a feedback note, append it to
`docs/specs/2026-05-16-agent-argument-graphs.md` under a `## Field Notes` section
(create the section if it does not exist). Do not rewrite the spec — append only.

**Commit:** Commit both files with a message in the form:
```
Add argument-sweep case study: <topic>

Sweep of [date] deliberation on <topic>. Vocabulary fit: <score>.
Key finding: <one sentence from Phase 4>.

Refs #(issue if applicable)
```

---

## What this skill is measuring

Each sweep is one data point in an ongoing experiment: does the seven-move vocabulary map
cleanly onto the deliberations that actually happen in this project? The accumulation of case
studies answers:

- Which move types appear most often?
- Which are most often implicit (and therefore most valuable to make explicit)?
- Are there move types the vocabulary is missing?
- Is the PRESUME/UNDERCUT pair doing the work we expect — surfacing hidden assumptions?
- Does the vocabulary feel natural to use, or does it require effort that breaks conversational
  flow?

The answers inform the system prompt (Phase 1 vocabulary work) and, eventually, the graph
storage design (Phase 2 infrastructure). The skill is the feedback loop between theory and
practice.

---

## Example output (abbreviated)

From the deliberation in this session on `WorkItem` persistence layer design:

```
Argument graph — WorkItem persistence approach:

CLAIM [agent]: approach A (Panache active record) is preferable on simplicity grounds
  SUPPORT: zero additional beans; standard Quarkus pattern
  PRESUME [agent]: consumer unit tests without a Quarkus container are not a requirement
    ← UNDERCUT [human]: Store SPI is the established pattern here
                        (ChannelStore, MessageStore, InstanceStore, DataStore all follow it)
CONCEDE [agent]: the PRESUME was wrong for this codebase
REVISED CLAIM [agent]: approach C (Store SPI) — aligns with convention; enables InMemory testing

Standing at close:
  Accepted: approach C (Store SPI)
  Rejected: approaches A and B (superseded by convention, not rebutted on technical merit)
  Unresolved: none
```

Vocabulary coverage: Clean (100% — all moves classified)
Explicit vs implicit: 0% explicit (vocabulary not yet in use); 100% reconstructable
Key finding: PRESUME was the implicit hinge — the agent's recommendation was driven entirely
by an assumption that was never surfaced. Making PRESUME explicit would have produced the
correct answer in the first response.

---

*Part of the Qhorus argument layer — `docs/specs/2026-05-16-agent-argument-graphs.md`*
*Skill version: 2026-05-16*
