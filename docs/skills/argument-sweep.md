# deliberation

Captures the reasoning behind project decisions as structured argument graphs — not what was
decided (ADRs do that) but *how* the conclusion was reached: every position, assumption,
challenge, concession, and revision that produced it.

Complements:
- **adr** — records the final decision and rationale; deliberation records the deliberative
  process that produced it; a significant deliberation should chain to `adr`
- **java-update-design** — keeps `DESIGN.md` accurate after architecture changes; deliberation
  captures *why* the architecture changed before the change is committed
- **java-code-review** — reviews code correctness; deliberation records the reasoning behind
  approach selection when multiple options were argued

---

## When to use

A deliberation record is worth capturing when:
- Three or more positions were considered and argued
- An assumption was corrected mid-discussion (UNDERCUT likely present)
- An approach changed from the initial recommendation (REVISE likely present)
- The decision has architectural or long-term consequences

Below this threshold, a design journal entry or inline comment in the ADR is sufficient.

---

## Modes

**Workspace mode** — when an epic branch is active (`epic-*`) and `design/.meta` and
`design/JOURNAL.md` exist:
→ Writes a journal entry to `design/JOURNAL.md` with the argument graph embedded

**Direct mode** — otherwise:
→ Writes a standalone file to `docs/deliberations/YYYY-MM-DD-<slug>.md`

---

## Workflows

### CAPTURE

Formalise a single deliberation from the current session.

1. **Identify the deliberative segment** in the current conversation — the stretch where
   positions were taken, challenged, or revised.

2. **Extract argument moves.** Read through and classify each logical act using the seven
   vocabulary types. Note each as:
   - *explicit* — the participant used the vocabulary word
   - *implicit* — the move happened but was not labelled; the classification is reconstructed

   The seven moves:

   | Move | What it captures |
   |---|---|
   | **CLAIM** | A position asserted with stated reasoning |
   | **PRESUME** | An assumption the argument depends on — stated or implicit |
   | **SUPPORT** | Evidence or reasoning backing a CLAIM |
   | **REBUT** | Direct contradiction of a specific CLAIM |
   | **UNDERCUT** | Challenge to the assumption or evidence behind a CLAIM, not the CLAIM itself |
   | **CONCEDE** | Explicit or implicit withdrawal of a prior position |
   | **REVISE** | An updated CLAIM in light of a REBUT or UNDERCUT |

   Flag any move that does not fit as UNCLASSIFIED.

   **Pay special attention to implicit PRESUMEs** — assumptions that drove the conclusion
   without being stated. These are the most common source of avoidable argument. Surfacing them
   is the highest-value output of the record.

3. **Reconstruct the argument graph** in checkpoint form:

   ```
   CLAIM [author]: [position]
     SUPPORT: [evidence or reasoning]
     PRESUME [author]: [assumption — mark *(implicit)* if never stated]
       ← UNDERCUT [author]: [why the assumption failed]
   CONCEDE [author]: [what was withdrawn — mark *(implicit)* if silent]
   REVISED CLAIM [author]: [updated position]
     SUPPORT: [new reasoning]

   Standing at close:
     Accepted: [positions not successfully challenged]
     Rejected:  [positions withdrawn or rebutted]
     Unresolved: [positions where challenge and defence remain open — none if clean]
   ```

4. **Vocabulary fit assessment:**
   - *Clean* — all moves classified, none UNCLASSIFIED
   - *Mostly clean* — one or two UNCLASSIFIED
   - *Partial* — significant minority UNCLASSIFIED
   - *Poor* — majority UNCLASSIFIED; vocabulary may not fit this deliberation type

5. **Draft the entry** using the template below.

6. **Present to user** for review. Wait for explicit **YES** before writing.

7. **Write the file:**
   - Workspace mode: append journal entry to `design/JOURNAL.md`
   - Direct mode: write `docs/deliberations/YYYY-MM-DD-<slug>.md`

8. **Commit:**
   ```
   deliberation: capture [topic] — [vocabulary fit], [N] implicit PRESUMEs
   ```

9. **If the decision is architecturally significant**, offer to chain to `adr`.

10. **If UNCLASSIFIED moves appeared**, offer to append a vocabulary feedback note to
    `docs/specs/2026-05-16-agent-argument-graphs.md` under `## Field Notes`.

---

### SWEEP

Scan the entire session for deliberative segments. Batch-capture all that meet the threshold.

1. **Identify deliberative segments** — stretches where positions were taken, challenged, or
   updated. Mark start and end of each.

2. **Score each segment** on three dimensions (1–3 each):

   | Dimension | 1 | 2 | 3 |
   |---|---|---|---|
   | **Consequence** | Local, easily reversed | Affects module or feature boundary | Architectural or long-term |
   | **Contention** | One clear option, token alternatives | Two or more genuine positions argued | Significant tension; UNDERCUT or REVISE present |
   | **Implicit structure** | Argument was already explicit | Some implicit moves surfaced | Key PRESUME was implicit; surfacing it changed the outcome |

   Threshold: **≥ 5**. Below threshold: note to user but do not capture.

3. **Extract moves** for each segment above threshold (as in CAPTURE step 2).

4. **Present candidates** as a batch. Show topic, score, and fit summary for each. User
   confirms, skips, or requests revision.

5. **Write all confirmed entries** (workspace or direct mode as above).

6. **Commit atomically:**
   ```
   deliberation: sweep [date] — N captured, M skipped (below threshold)
   ```

7. **Offer `adr`** for any captured entry with consequence = 3.

---

### RATIFY

Mark a captured argument graph as confirmed by a participant.

1. Read the entry. Present the **Argument Graph** section to the participant.
2. Participant confirms ("yes, this is accurate") or raises a correction.
   - Correction: identify which move is wrong, update the graph, re-present.
3. Update the entry's `ratified_by` field.
4. When all participants listed have ratified, update `verified: true`.
5. Commit:
   ```
   deliberation: ratify [filename] — [participant] confirmed ([N] of [M])
   ```

---

## File Templates

### Direct mode — `docs/deliberations/YYYY-MM-DD-<slug>.md`

```markdown
---
title: "[Decision reached] over [alternatives]"
date: YYYY-MM-DD
participants: [human, agent-or-reviewer]
vocabulary_fit: clean | mostly-clean | partial | poor
implicit_presumes: N
unclassified_moves: N
verified: false
ratified_by: []
---

## [Title]

**Motion:** [The proposition under deliberation]
**Participants:** [who argued]
**Context:** [channel, session, or case reference]

### Argument Graph

[Checkpoint-format argument graph — see CAPTURE step 3]

### Standing at Close

- **Accepted:** [positions not successfully challenged]
- **Rejected:** [positions withdrawn or rebutted]
- **Unresolved:** [open challenges at close — none if clean]

### Vocabulary Fit

- **Coverage:** [clean / mostly-clean / partial / poor]
- **Explicit / implicit:** [N% explicit; remainder reconstructed]
- **Implicit PRESUMEs:** [list each with one line]
- **UNCLASSIFIED moves:** [N — describe if any]
- **Key finding:** [One sentence — most useful observation]

### Consequences

[What this decision makes easier. What it makes harder.]

*Ratification: [pending / complete — [date]]*
```

---

### Workspace mode — journal entry appended to `design/JOURNAL.md`

```markdown
### §Deliberation — [Topic] — [YYYY-MM-DD]

**Motion:** [proposition under deliberation]
**Participants:** [who argued]
**Vocabulary fit:** [clean / mostly-clean / partial / poor]

**Argument Graph:**

> CLAIM [author]: [position]
>   SUPPORT: [evidence]
>   PRESUME [author]: [assumption *(implicit)* if not stated]
>     ← UNDERCUT [author]: [why it failed]
> CONCEDE [author]: [what was withdrawn]
> REVISED CLAIM [author]: [updated position]

**Accepted:** [positions at close]
**Rejected:** [positions withdrawn or rebutted]
**Key finding:** [one sentence — most important observation for the record]

*[Ratification status]*
```

---

## Vocabulary Feedback Note

When UNCLASSIFIED moves appear or a pattern of implicit moves suggests a vocabulary gap:

```markdown
## Field Notes — [date]

**Source:** [deliberation filename or session description]

**UNCLASSIFIED moves:** [N — what they were doing that the vocabulary didn't cover]
  Candidate move type: [proposed label, or "needs design"]

**Implicit PRESUME pattern:** [how often; what kind of assumptions went unstated]
  Suggested system prompt emphasis: [what to strengthen]

**Move confusion:** [which pairs were hard to distinguish in practice]
  Suggested worked example: [what to add to the spec]
```

Append to `docs/specs/2026-05-16-agent-argument-graphs.md` under `## Field Notes`.

---

## Skill Chaining

**Feeds from:**
- `java-code-review` — offer SWEEP after any review session where approaches were debated
- Any design discussion with three or more options considered

**Feeds into:**
- `adr` — when a deliberation produced an architecturally significant decision (consequence = 3)
- `java-update-design` — when the accepted position changes the architecture documented in
  `DESIGN.md`

**Never:**
- Commit without explicit user confirmation
- Modify `DESIGN.md` directly — that is `java-update-design`'s responsibility
- Create an ADR without chaining to the `adr` skill — ADR format and numbering are managed there
