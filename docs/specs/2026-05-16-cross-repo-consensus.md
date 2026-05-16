# Cross-Repo Consensus — Design Exploration

**Date:** 2026-05-16  
**Status:** Draft for discussion  
**Context:** Automating the human-mediated copy-paste workflow between Claude instances across repositories

---

## The Problem

A recurring workflow in multi-repo development: Claude A (working in repo X) detects that something needs to happen in repo Y. The human carries the context manually — copying Claude A's description into a separate Claude B session in repo Y, carrying Claude B's output back to Claude A for validation, and repeating until both Claudes agree or the human decides. Two or three round-trips is common; the human is doing mechanical routing rather than meaningful oversight.

The cost is not just time. Context degrades with each transfer: what Claude A actually meant, what constraints it was operating under, what it would accept as a valid resolution — none of this travels with the copied text. Claude B interprets from the fragment it receives. Claude A reviews the result without knowing how Claude B understood the brief. Disagreements surface at the wrong level. The human cannot tell whether a dispute is fundamental or a communication artefact.

What is missing is not intelligence — both Claudes have that. What is missing is a shared channel, a structured record of the argument, and a human interface that shows the state of agreement at a glance rather than requiring the human to hold the context of two separate sessions simultaneously.

---

## The Insight

This workflow is already multi-agent deliberation. The human is acting as a Qhorus channel — routing messages between two agents, carrying context that neither has directly, and eventually deciding when consensus is close enough to proceed. The pieces to automate it are largely in place:

| What the human does manually | What the infrastructure provides |
|---|---|
| Carries context from Claude A to Claude B | Qhorus shared channel with full message history |
| Carries output back for validation | Same channel — Claude A reads Claude B's response directly |
| Tracks what's agreed vs. disputed | Argument graph — CLAIM/REBUT/CONCEDE/REVISE record |
| Decides when consensus is reached | Ratification step — both agents confirm |
| Intervenes when they disagree | Human-in-the-loop approval gate on each round-trip |

What is genuinely new — not already in the stack — is the **human approval gate UI**: a view that shows the current state of the cross-repo argument, lets the human read and approve each message before it crosses the repo boundary, and surfaces where the two Claudes still disagree.

---

## The Architecture

```
┌─────────────────────┐         ┌─────────────────────┐
│   Claude A          │         │   Claude B          │
│   (repo-qhorus)     │         │   (repo-devtown)    │
│                     │         │                     │
│  Detects cross-repo │         │  Processes brief    │
│  work item          │         │  Produces output    │
│  Reviews response   │         │  Responds to review │
└────────┬────────────┘         └──────────┬──────────┘
         │                                 │
         │         Qhorus channel          │
         │    "cross-repo/<case-id>"       │
         ├────────────────────────────────►│  COMMAND: brief + context
         │◄────────────────────────────────┤  RESPONSE / DONE / DECLINE
         │────────────────────────────────►│  REBUT / QUERY (if disagreement)
         │◄────────────────────────────────┤  REVISE / DONE
         │                                 │
         │    ▲ human sees every message   │
         │    ▲ human approves forwarding  │
         │    ▲ human can intervene        │
         │                                 │
┌────────┴─────────────────────────────────┴──────────┐
│   CaseHub case: cross-repo work item                │
│   DevTown: WorkItem routing + human review inbox    │
│   Qhorus: channel + normative ledger + arg graph    │
│   Claudony: terminal management + approval UI       │
└─────────────────────────────────────────────────────┘
```

### CaseHub

The cross-repo work item is a CaseHub case that spans both repositories. It exists from the moment Claude A identifies the need until both Claudes agree the work is done. The case tracks: what repo A needs from repo B, what repo B has produced, what the outstanding disagreements are, and who is accountable for each.

This is already the CaseHub model — cases that span agents, with obligation tracking. The cross-repo scenario is just a case whose agents happen to live in different codebases.

### DevTown

DevTown's WorkItem inbox and trusted agent routing handle the job assignment side: when Claude A identifies cross-repo work, it creates a DevTown work item targeting repo B. DevTown routes it to Claude B (the appropriate agent for that repository) and manages the SLA. The human review workflow in DevTown is the approval gate — the human sees the work item, reads what Claude A sent, and approves forwarding it to Claude B before anything crosses the repository boundary.

This is a natural DevTown feature rather than a new tool. DevTown already handles trusted agent routing and human review workflows; the cross-repo consensus scenario exercises both.

### Qhorus

The shared channel `cross-repo/<case-id>` is where the two Claudes communicate. Both are registered agents on a shared Qhorus instance (or Qhorus federation, if the repos run separate instances). The normative ledger records every message — the full argument history is tamper-evident and queryable. The argument graph layer (from the argument graphs spec) records the logical structure: what was CLAIMed, what was REBUTted, what was CONCEDEd.

The Qhorus message types map naturally:

| Moment | Message type |
|---|---|
| Claude A sends the cross-repo brief | COMMAND (creates an obligation on Claude B) |
| Claude B acknowledges | STATUS |
| Claude B delivers | DONE or RESPONSE |
| Claude A disagrees | QUERY (asks for revision) or starts argument exchange |
| Claude B can't fulfil | DECLINE (with reason) |
| Claude B redirects | HANDOFF (to a different agent if Claude B is not the right one) |

The argument vocabulary sits above this — inside the message content — structured by CLAIM, REBUT, UNDERCUT, CONCEDE, REVISE when the two Claudes are negotiating the substance of what repo B should produce.

### Claudony

Claudony manages the Claude instances and shows the human the channel state. The approval UI is a Claudony panel: a view of the `cross-repo/<case-id>` channel, the current argument graph (what's agreed, what's disputed), and a simple approve/redirect/intervene action on each pending message.

This is the piece the human actually interacts with — not a copy-paste buffer but a structured view of the deliberation in progress.

---

## The Human Interface

What changes for the human:

**Now:** Read Claude A's output in terminal 1. Copy. Switch to terminal 2. Paste as a new message. Read Claude B's output. Copy. Switch back. Paste. Repeat. Hold the full context of both sessions in your head to understand what each is objecting to.

**With this tool:** Open the cross-repo consensus panel in Claudony. See the current state of the channel: what Claude A sent, what Claude B produced, what the argument graph shows is agreed and what is still disputed. Approve the next message to be forwarded, or intervene with a clarification. The panel shows you the delta — not the full transcript of both sessions, but the specific points still in contention.

The human's role shifts from **message carrier** to **overseer and decision-maker**. The mechanical routing is automated. The human only engages when a message needs approval, when the two Claudes cannot resolve a disagreement, or when the case needs a final call.

---

## The Argument Vocabulary in This Context

The back-and-forth between two Claudes on a cross-repo task is exactly the deliberation pattern the argument vocabulary is designed for. Claude A might CLAIM that a schema change in repo B is necessary; Claude B might UNDERCUT that claim by noting the schema already supports the requirement via a different field. Claude A CONCEDEs or REVISEs. The argument graph records the chain.

The human approval gate is the **ratification point**: before a CONCEDE or REVISE from Claude B is forwarded to Claude A as the agreed resolution, the human reads it and confirms it is actually what they want. This is where the argument graph is most valuable — the human does not need to re-read the full exchange, only the current state of the graph: what's accepted, what's still open.

The deliberation-probe skill (from the argument graphs exploration) can be run on any cross-repo consensus session to evaluate how well the vocabulary mapped onto the actual exchange. This is the feedback loop between the cross-repo tool and the argument vocabulary design.

---

## What Is Actually New

Most of this composes existing pieces. What needs to be built:

**1. Cross-repo Qhorus channel provisioning.** When a cross-repo work item is created in DevTown, a `cross-repo/<case-id>` channel is automatically provisioned in Qhorus. Both Claude instances are registered as agents on this channel with appropriate capabilities.

**2. Human approval gate in DevTown.** Before a message crosses the repo boundary (COMMAND from A to B, or DONE/RESPONSE from B back to A), it enters the DevTown human review inbox. The human reads it, optionally edits it, and approves forwarding. The gate is configurable: high-trust pairs can auto-forward; new or uncertain pairs require human approval on every message.

**3. Argument graph view in Claudony.** A panel in Claudony's terminal management UI that shows the current state of a cross-repo channel: the argument graph (agreed/disputed), the message history, and the pending approval queue. The human interacts with this panel rather than with two separate terminals.

**4. Context packaging.** When Claude A initiates a cross-repo COMMAND, it produces a structured brief: what is needed, why, what constraints apply, what Claude A will use to validate the result. This brief is richer than copy-pasted text — it is a structured artefact that Claude B can reference without needing the full context of Claude A's session.

---

## Phases

**Phase 0 — Structured copy-paste (now, no new infrastructure)**

Use the argument vocabulary in the existing copy-paste workflow. When carrying text between Claudes, structure it explicitly: "Claude B: I CLAIM you need to do X. PRESUME you don't already have Y — correct me if wrong." This alone improves the quality of the hand-off and begins generating data for the deliberation-probe. No new tools required.

**Phase 1 — Shared Qhorus channel (first build)**

Provision a shared Qhorus channel that both Claude instances can post to directly via MCP tools. The human still approves each message (copy-paste for approval) but the messages are now structured and logged in the normative ledger. The argument graph is automatically extracted at each checkpoint.

**Phase 2 — DevTown approval gate**

Add the human approval gate to DevTown's WorkItem inbox. Messages queue there before forwarding. Human approves or edits in DevTown rather than by copy-pasting. The channel flows automatically once approved.

**Phase 3 — Claudony consensus panel**

Add the argument graph view to Claudony. Human interacts with the consensus state rather than reading individual messages. High-trust pairs auto-forward; the human only sees messages that need a decision.

---

## Open Questions

**1. Qhorus federation.** If Claude A and Claude B are on separate Qhorus instances (one per repo), does the channel need to federate between them, or is there a shared Qhorus instance for cross-repo work? The shared instance approach is simpler but requires an always-on Qhorus server; federation is more complex but more decentralised.

**2. Context packaging format.** What exactly travels in the COMMAND brief? Plain text plus artefact refs (the current Qhorus model) may not be enough — Claude B needs to understand Claude A's constraints and validation criteria, not just the task. This may require a richer structured format specific to cross-repo work items.

**3. Trust between Claudes.** If Claude B is operating in a sensitive repository (payment processing, compliance), should its outputs be auto-forwarded to Claude A, or should they always pass through human review? The DevTown trust scoring (EigenTrust from the normative ledger) could govern this — but only after enough history exists to compute scores.

**4. Conflict resolution authority.** When the two Claudes cannot reach consensus and the human intervenes, who is right by default — Claude A (the initiating repo) or Claude B (the implementing repo)? This is a policy question, not a technical one, but the tool needs to encode an answer.

---

*Built on: Qhorus (channel + normative ledger + argument graph) · CaseHub (case management) · DevTown (WorkItem routing + human review) · Claudony (terminal management + approval UI)*  
*References: `docs/specs/2026-05-16-agent-argument-graphs.md` — the argument layer this tool surfaces*  
*This document is a design exploration. It does not commit to an implementation schedule.*
