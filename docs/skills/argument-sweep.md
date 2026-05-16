# deliberation-probe

A read-only evaluation skill. Scans a conversation and reports how well the Qhorus argument
vocabulary maps onto the actual back-and-forth. No files are written. Nothing is committed.
The output is a report for the human to evaluate — does the vocabulary fit the way we actually
argue? Where does it work? Where does it fall short?

Run this across several conversations and projects before building anything. The report is the
product.

---

## Vocabulary under test

Seven moves. These are what we are probing for:

| Move | What it should capture |
|---|---|
| **CLAIM** | A position asserted with stated reasoning |
| **PRESUME** | An assumption the argument depends on — stated or implicit |
| **SUPPORT** | Evidence or reasoning backing a CLAIM |
| **REBUT** | Direct contradiction of a specific CLAIM |
| **UNDERCUT** | Challenge to the assumption or evidence, not the conclusion |
| **CONCEDE** | Withdrawal of a prior position |
| **REVISE** | An updated CLAIM caused by a REBUT or UNDERCUT |

---

## What to do

### Step 1 — Find the deliberative segments

Read the current session. A deliberative segment is any stretch where a position was taken and
either challenged, conceded, or revised. Purely informational exchanges (question/answer, status
updates, instructions) are not deliberative — skip them.

Note the topic of each segment in one line.

### Step 2 — Reconstruct the argument moves

For each deliberative segment, read through and classify every logical act using the seven
vocabulary types above.

For each move record:

- **Type:** which of the seven it is (or UNCLASSIFIED if it genuinely does not fit)
- **Author:** who made the move (human, agent name, or "system")
- **Substance:** the move in one sentence
- **Explicit or implicit:** did the author use the vocabulary word, or is this a reconstruction?

Mark implicit PRESUMEs clearly — these are assumptions that drove the argument without being
stated as assumptions. They are the most important thing to find.

### Step 3 — Reconstruct the argument graph

Produce a checkpoint-style summary for each segment:

```
CLAIM [author]: [position]
  SUPPORT: [evidence or reasoning]
  PRESUME [author]: [assumption — mark *(implicit)* if not stated]
    ← UNDERCUT [author]: [why the assumption failed]
CONCEDE [author]: [what was withdrawn]
REVISED CLAIM [author]: [updated position]

Standing at close:
  Accepted:   [positions not successfully challenged]
  Rejected:   [positions withdrawn or defeated]
  Unresolved: [open challenges at close]
```

### Step 4 — Score vocabulary fit

For each segment, score on three questions:

**Coverage:** What fraction of moves mapped to one of the seven types?
- Clean — all moves classified
- Mostly clean — one or two UNCLASSIFIED
- Partial — several UNCLASSIFIED
- Poor — majority UNCLASSIFIED; vocabulary may not fit this deliberation type

**Explicit ratio:** What fraction of moves were already using the vocabulary words explicitly?
This measures current adoption, not vocabulary fit. A fully implicit but fully reconstructable
segment is evidence the vocabulary is right — the structure was there, just unlabelled.

**Hinge move:** Was there a PRESUME + UNDERCUT pair that changed the outcome? If yes, did
surfacing it (even implicitly) produce a better conclusion than the starting positions?

### Step 5 — Report

Produce a single report covering all deliberative segments found in the session.

Format:

```
DELIBERATION PROBE — [session description] — [date]

Segments found: N

---
SEGMENT [N]: [topic]

Argument graph:
[checkpoint-format reconstruction]

Vocabulary fit:    [clean / mostly-clean / partial / poor]
Explicit ratio:    [N]% (M of K moves used vocabulary labels)
Implicit PRESUMEs: [N] found — [list each in one line]
UNCLASSIFIED:      [N] — [describe each: what was the move doing?]
Hinge move:        [yes/no — if yes, describe the PRESUME + UNDERCUT pair]

---
[repeat for each segment]

---
SUMMARY

Total moves classified: N
Total UNCLASSIFIED:     N ([N]%)
Vocabulary fit overall: [clean / mostly-clean / partial / poor]
Most common implicit move type: [which of the 7 was most often unlabelled]
Most important finding: [one sentence — what does this session tell us about the vocabulary?]

Vocabulary gaps (if any):
  [For each UNCLASSIFIED cluster: what move type is missing? Candidate label?]

Recommendation:
  [Does the vocabulary fit well enough to use in system prompts? What needs strengthening first?]
```

---

## What to look for across multiple sessions

Run the probe on several different conversation types:
- Human + single agent design discussion
- Multi-agent code review
- Approach selection (3+ options presented and debated)
- Short focused exchange (1–2 positions, quick resolution)

The questions to answer over time:
- Which of the seven moves appears most often?
- Which moves are most often implicit? (These are the ones to emphasise in the system prompt.)
- Are there move types that keep appearing as UNCLASSIFIED? (These are vocabulary gaps.)
- Does the PRESUME + UNDERCUT pair keep being the hinge? (This validates the design.)
- Do short exchanges have the same classification rate as long ones?

No single session answers these questions. The probe is a data collection tool. The answers
accumulate across sessions and projects.

---

## Output

Print the report to the conversation. Do not write files. Do not commit anything. The human
reads it, evaluates it, and decides whether the vocabulary is working.
