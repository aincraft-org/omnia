---
name: spec-driven-development
description: Use when starting any non-trivial feature, refactor, migration, API or schema change in a Paper/Minecraft server project, when requirements are ambiguous or unstated, or when the user asks to spec out requirements, write a design doc, or plan before coding. Triggers include "requirements", "design doc", "SDD", "spec driven", "plan this feature", and feature requests arriving without spec context. Not for questions, typos, or one-line mechanical fixes.
---

# Spec-Driven Development

**The spec is the contract; code is a consequence of it.** Never implement non-trivial work directly from a vague prompt. Pipeline: **Triage → Specify → Clarify → Plan → Tasks → Implement → Validate**, with a hard human-approval gate before any implementation begins.

Methodology adapted from [GitHub Spec Kit](https://github.github.io/spec-kit/) (Specify → Clarify → Plan → Tasks → Implement), [pi-sdd-kit](https://github.com/felipefontoura/pi-sdd-kit) (explicit approval gates — artifact existence is never approval), and [Kiro Specs](https://kiro.dev/docs/specs/) (structured acceptance criteria). Sources verified 2026-08-21. Execution is agent-driven: each session runs the current plan's task list directly or a subagent per task, with a review gate before a task closes.

## Triage first

State the classification and why, out loud, before anything else:

| Path | When | Ceremony |
|---|---|---|
| **Skip** | questions, explanations, typos, one-line mechanical fixes | none — just answer/fix |
| **Quick** | small, well-understood change, ≤2 files, no behavior contract change | state approach in chat; no artifacts |
| **Full** | new features, behavior/API/data/schema changes, multi-file or cross-skill work | complete pipeline below |

Ratchet: mid-work discovery of hidden complexity upgrades Quick → Full. Never downgrade Full because momentum feels good. A feature request arriving with no spec context routes through Specify even if it "seems small" — "add dark mode" always has spec implications.

## Artifacts (repository convention)

| Artifact | Path | Answers |
|---|---|---|
| Design (spec) | `docs/superpowers/specs/YYYY-MM-DD-<slug>-design.md` | WHAT and WHY |
| Implementation plan | `docs/superpowers/plans/YYYY-MM-DD-<slug>.md` | HOW, task-by-task |

Both use today's UTC date in the filename. Worked examples: `2026-08-21-static-analysis-ci-*` in this repository. Plans use checkbox (`- [ ]`) task syntax so the task list is directly consumable by any agent session.

## Phase 1 — Specify

Inspect the repository first: relevant skills, existing patterns, README, neighboring specs. Never invent conventions the repo already has.

Write the design doc with stable identifiers:

- **Goal** — problem and desired end state, one paragraph.
- **Scope** — what changes, what explicitly does not. Out-of-scope section must be non-empty.
- **Functional requirements** — `FR-001`, `FR-002`, …; each in "shall" language with a testable acceptance criterion (`AC-1`, …).
- **Non-functional requirements** — `NFR-001`, …; measurable only ("< 200 ms p95", never "fast").
- **Failure handling** — what happens on each error path.
- **Verification policy** — how the finished work will be proven (exact commands, expected results).

**GATE 1:** Present the spec. Stop. Proceed only on explicit human approval. A saved file is not approval.

## Phase 2 — Clarify

Ask only what the repository cannot answer. For genuinely ambiguous decisions, present 2–4 concrete options with the impact of each — never open-ended questions. Record decided answers in the spec's relevant section. Do not stall on low-cost decisions: pick the boring option, note the ruling, move on; only irreversible, destructive, security-sensitive, or publish-side actions are hard stops.

## Phase 3 — Plan

Update or extend the design doc into implementation shape (or, for larger efforts, the matching plan file):

- Affected files/components, exact and exhaustive.
- Interfaces consumed and produced (contracts downstream tasks depend on).
- Reuse of existing patterns — a second convention beside an existing one is a defect.
- Alternatives considered, risks and mitigations, rollback strategy.
- Atomic change boundaries mapping to commits (this repo: one skill change per commit).

**GATE 2:** Present the plan. Stop. Explicit approval required.

## Phase 4 — Tasks

Break the plan into ordered, independently verifiable tasks. Every task carries:

- Task ID and the requirement IDs it satisfies, inline: `**Task 2** [FR-002, NFR-001]`
- Exact files touched.
- Step-by-step actions with real content (no placeholders, no "TODO: implement").
- Its own verification step: exact command plus expected observable result.

Ordering: prerequisite research → shared contracts → independent slices → integration → final verification. Tasks sharing a file or interface must declare the handoff contract up front.

## Phase 5 — Implement and validate

Execute tasks in one session tracking the plan checkbox list, or dispatch one agent per task when slices are independent. Rules:

- A task closes only when its verification command passes **and** the implementation still matches the spec. Divergence updates the artifacts first, then the code.
- Never silently expand scope. Discovered adjacent work becomes a new spec'd task or gets explicitly deferred.
- Final validation runs once at the end across all changed surfaces, then remove any scratch workspace/ledger files the execution created.

## Task-runner contract

- The plan file is the source of task state: tick a checkbox only when that task's verification command passes and the implementation still matches the spec.
- A task's verification is its own exact command plus expected result — never a substitute check.
- Independent tasks may run in parallel only when they do not share files or interfaces; declare handoff contracts up front otherwise.

## Change control

A requirement change walks the chain in order: spec → plan → tasks → approval → code. Code is never allowed to become the undocumented source of truth. If implementation must differ from approved artifacts, stop and amend the artifacts first.

## Common mistakes

| Wrong | Right | Why |
|---|---|---|
| Implementing from a vague prompt because it "seems clear" | Triage; Full path requires Gate 1 first | Ambiguity compounds into rework; the prompt is not a contract |
| Treating a saved `*-design.md` as license to build | Explicit human approval at every gate | File existence proves drafting, not sign-off |
| Writing the plan before Gate 1 passes | Gates in order: spec → approve → plan → approve → tasks | Design churns while requirements are still moving |
| Tasks without verification commands | Every task names its exact check and expected output | "Done" becomes unfalsifiable |
| Adjective NFRs ("fast", "robust") | Measurable thresholds ("< 200 ms p95") | Agents cannot verify adjectives |
| Updating code after a requirement change without touching the spec | Change control starts at the spec | Code silently becomes the undocumented source of truth |
| Full pipeline for a typo fix | Triage Skip/Quick paths | Ceremony kills throughput; scale the ritual to the risk |
| Inventing a second convention beside an existing repo pattern | Phase 3 reuses the established pattern | Two competing conventions is a defect, not flexibility |
