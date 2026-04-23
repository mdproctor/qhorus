# Quarkus Qhorus — Session Handover
**Date:** 2026-04-24 (continued session — Jlama fixed; CI tests; repo → casehubio)

## What Was Done This Session (delta from previous handover)

- **Jlama fully fixed** — 4 bugs patched in `~/claude/quarkus-langchain4j`; examples now boot and run
- **`examples/type-system/`** — 13 fast regression tests (no model, no LLM); runs in CI in 2.6s
- **`examples/agent-communication`** — moved behind `-Pwith-llm-examples` profile; CI no longer tries to run LLM tests
- **CI build.yml** — removed Jlama install step (agent-communication now behind profile)
- **Claudony** — VALID_HUMAN_TYPES and UI dropdown migrated from REQUEST → QUERY/COMMAND/DECLINE
- **Repo transferred** — `mdproctor/quarkus-qhorus` → `casehubio/quarkus-qhorus`; local remote updated

## Current State

*Unchanged — `git show HEAD~1:HANDOFF.md`* for everything except:

- **Repo:** `github.com/casehubio/quarkus-qhorus` (push URL updated locally)
- **Jlama status:** examples fully working; PRs pending upstream to quarkiverse/quarkus-langchain4j (3 commits in `~/claude/quarkus-langchain4j`)
- **First LLM run:** model not yet downloaded. Run `mvn test -pl examples/agent-communication -Pwith-llm-examples` once to cache ~700MB to `~/.jlama/`

## Immediate Next Steps

1. **Run LLM examples once** — `JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn test -pl examples/agent-communication -Pwith-llm-examples -Dno-format` — gets classification accuracy numbers for the paper
2. **PR Jlama fixes upstream** — 3 commits in `~/claude/quarkus-langchain4j` to quarkiverse/quarkus-langchain4j
3. **CommitmentStore (v2)** — generalise `PendingReply`; `commitmentId` field in `Message` is the bridge
4. **Normative ledger entries (v2)** — expand `LedgerWriteService` to record COMMAND, DECLINE, FAILURE, HANDOFF, DONE
5. **Paper** — contact Governatori; ADR-0005 is ready to share; session capture at `~/claude/2026-04-23-speech-acts-deontic-session-capture.md`

## References

| What | Path |
|---|---|
| ADR-0005 (theoretical foundation) | `adr/0005-message-type-taxonomy-theoretical-foundation.md` |
| Type-system CI tests | `examples/type-system/src/test/java/.../MessageTaxonomyTest.java` |
| LLM examples README | `examples/agent-communication/README.md` |
| Jlama fixes (local, PRs pending) | `~/claude/quarkus-langchain4j/` (3 commits) |
| Research session capture | `~/claude/2026-04-23-speech-acts-deontic-session-capture.md` |
| Previous handover | `git show HEAD~1:HANDOFF.md` |
