# Retrospective Issue Audit — quarkus-qhorus

Generated: 2026-04-20

## Summary

92 commits, 68 issues (#1–#68), 12 epics. All epics have populated Scope
checklists. Two epics use time-based naming; four commit clusters have no
issue coverage; four commits have uncaptured refs to existing issues.

---

## A — Epic Renames (capability-based naming)

| # | Current title | Corrected title |
|---|---|---|
| #32 | Phase 9 — A2A compatibility endpoint for external orchestrator interop | A2A compatibility endpoint for external orchestrator interop |
| #36 | Phase 10 — Human-in-the-loop controls | Human-in-the-loop controls and channel governance |

---

## B — New Issues for Uncovered Functional Commits

### B1 — Initial scaffold and Quarkiverse restructuring
**Scope:** Standalone (predates epic #1 child issues)
**Commits:**
- `7b3cafe` feat: initial Qhorus project scaffold (2026-04-13)
- `4f0475e` refactor: restructure to Quarkiverse extension conventions (2026-04-13)

**Proposed issue:** "Initial Qhorus scaffold and Quarkiverse extension structure"
`enhancement` | standalone

---

### B2 — quarkus-ledger supplement reconciliation
**Scope:** Standalone (related to #57 but separate breaking API changes)
**Commits:**
- `a58bd1c` fix: reconcile with quarkus-ledger supplement refactoring (2026-04-18)
- `a2cf9be` fix(test): switch from Flyway to Hibernate schema generation for tests (2026-04-18)

**Proposed issue:** "Reconcile with quarkus-ledger supplement refactoring — correlationId field + test schema"
`enhancement` | standalone

---

### B3 — Agent protocol comparison documentation
**Scope:** Standalone docs (no code impact)
**Commits (9):**
- `da878b4` docs: add Qhorus vs cross-claude-mcp comparison document (2026-04-14)
- `c7a58c4` docs: add A2A vs ACP vs Qhorus comparison document
- `1611642` docs: reference agent-protocol-comparison.md from DESIGN.md
- `dc01262` docs: add comprehensive multi-agent framework comparison table
- `7345b98` docs: clarify disposable vs durable DB distinction in comparison doc
- `f9c4704` docs: correct framing of Quarkiverse conventions
- `e265641` docs: incorporate nuanced feedback — native warm-up, LLM-agnostic
- `366346b` docs: full editorial pass on comparison doc
- `927dd15` docs: add phases 10-12 to design roadmap doc

**Proposed issue:** "Comparative analysis documentation — A2A, ACP, multi-agent frameworks, cross-claude-mcp"
`documentation` | standalone

---

### B4 — Retrospective blog documentation
**Scope:** Standalone docs
**Commits:**
- `7aa9755` docs: blog entry mdp03 — Phase 12 observability and Claudony MCP blocker (2026-04-16)
- `33fc59a` docs: retrospective blog entries — Day Zero through Phase 12 + ledger reconciliation (2026-04-20)

**Proposed issue:** "Retrospective blog documentation — session diary entries Phases 1–12"
`documentation` | standalone

---

## C — Commits with Missing Refs to Existing Issues (note only)

Cannot be fixed without amending commits. Documented for the record.

| Commit | Subject | Should ref |
|---|---|---|
| `b4cc0de` | test: creative edge-case review — 64 new tests, 2 critical bugs fixed | #21 |
| `ee6d912` | test: address Phase 2 code review | #20 |
| `c2b043a` | test: address Phase 3 code review — two critical bugs | #20 |
| `a855fda` | test: address Phase 1 code review — coverage gaps | #20 |
| `c89e4d5` | docs: ADR-0001 — MCP tool return type strategy | #55 |
| `7c46314` | docs: update ADR-0001 and DESIGN.md — @WrapBusinessError | #56 |
| `e475ae2` | docs: update ecosystem design doc reference to claudony | #1 |

---

## D — Excluded Commits (trivial, no issue needed)

| Commit | Reason |
|---|---|
| `ed46230`, `4a4428f`, `6300c63`, `169cf6ef`, `c3cb03f`, `7d650c6`, `22e9d75`, `69c6914` | Session handovers — operational artifacts |
| `bdf1716` | Session wrap / meta-docs |
| `4e1d151` | Idea log entry |
| `d56c78f` | chore: add .worktrees/ to .gitignore |
| `5e8d8fe` | chore: remove accidentally committed sources |
| `8168eea`, `1a560f6`, `eb9e8f7` | Project briefing docs |
| `d238b19` | Minor CLAUDE.md cleanup (1 line removed) |
| `a6a8bd3` | DESIGN.md sync — covered by epics #7 and #12 |
| `ff29335`, `a57655b` | DESIGN.md phase-completion status updates |
| `c0b880b` | DESIGN.md phase-completion status update |
| `a8620d9` | DESIGN.md phase-completion status update |
| `b379115` | DESIGN.md phase-completion status update |
| `646df78` | CLAUDE.md Work Tracking rule addition |

---

## Actions

1. Rename epic #32 (drop "Phase 9 —" prefix)
2. Rename epic #36 (drop "Phase 10 —" prefix)
3. Create and close 4 new issues (B1–B4)
4. Section C: note only, no git history amendment

