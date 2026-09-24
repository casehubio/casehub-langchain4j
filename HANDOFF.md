# Handoff — casehub-langchain4j

**Status:** First draft — repo scaffolded, specs written, ready for implementation.
**For:** Dmitrii Tikhomirov (treblereel)
**Date:** 2026-09-25

---

## What This Is

Enterprise enrichment for LangChain4j. CaseHub adds audit, tenancy,
governance, agent identity, typed channels, human-in-the-loop, and more
to existing LC4j applications — without replacing anything LC4j provides.

Read `docs/VISION.md` first. It explains the why, the what, and the
positioning. It's written to be shareable with the LC4j team.

---

## What's Done

### Repo scaffold
- GitHub repo: `casehubio/casehub-langchain4j` (public)
- Fork: `mdproctor/casehub-langchain4j`
- Remotes: `upstream` = casehubio, `origin` = mdproctor
- Maven multi-module with 15 modules (5 groups × 3 framework variants)
- Stub classes in each module showing the implementation pattern
- `bom/` and `examples/` modules

### Documentation
- `ARC42STORIES.MD` — 30 chapters across 4 phases. Your implementation roadmap.
- `docs/VISION.md` — shareable brief explaining the project
- `STRATEGY.md` — complementary positioning and expansion policy
- `docs/guides/contributor-guide.md` — module architecture, conventions
- `docs/guides/consumer-guide.md` — getting started, module reference
- `CLAUDE.md` — project instructions for Claude sessions

### Specs (in engine workspace)
Detailed implementation specs are at:
`~/claude/public/casehub/engine/specs/casehub-langchain4j/`

| File | Content |
|---|---|
| `decisions.md` | 8 design decisions with rationale |
| `2026-09-24-casehub-langchain4j-design.md` | Architecture overview |
| `2026-09-24-phase-1-enterprise-concerns.md` | Phase 1 implementation detail — class signatures, CDI/Spring wiring, tests |
| `2026-09-24-phase-2-enterprise-implementations.md` | Phase 2 design |
| `2026-09-24-phase-3-application-showcase.md` | Phase 3 design (needs revision — see below) |

---

## What's NOT Done

### Must do before implementation starts

1. **Move agent-langchain4j from platform** — the `agents-core/`,
   `agents/`, `agents-spring/` modules are empty stubs. The actual code
   lives in `platform/agent-langchain4j-core`,
   `platform/agent-langchain4j`, `platform/agent-langchain4j-spring`.
   Move the source code, update artifact coordinates, update consumers
   (engine, blocks, etc.).

2. **Parent repo registration** — follow `parent/docs/new-repo-checklist.md`
   steps 6–13: BOM entries, CI dispatch chain, dashboard, README badges,
   architecture diagram, guide sync.

3. **CI workflow** — `.github/workflows/publish.yml` with
   `repository_dispatch` trigger for upstream changes.

4. **Workspace setup** — `~/claude/public/casehub/casehub-langchain4j/`
   with symlinks, workspace git repo, blog-routing.yaml. Follow
   checklist steps 14.

5. **Epic issues** — create epics matching the ARC42STORIES journeys.
   Create child issues for C1 (scaffold completion) and C2–C5 (Phase 1
   core modules).

### Specs that need revision

- **Phase 2 spec** — needs to be rewritten to cover eidos, qhorus, work,
  and neocortex cognitive capabilities (the current version only covers
  CBR memory, SPLADE, corpus — that's now Phase 3).
- **Phase 3 spec** — needs to be rewritten as the old Phase 2 content
  (enterprise SPI implementations).
- **Phase 4 spec** — doesn't exist yet. Covers engine execution kernel,
  blocks orchestration patterns. Write when approaching Phase 4.

All specs are first drafts and may be revised.

---

## How To Work Through This

### The short version

Follow `ARC42STORIES.MD` chapter by chapter. Each chapter tells you
what to build, which layers it touches, and what issues to create.
Phase 1 chapters have full implementation detail. Later phases have
lighter entries that you flesh out as you approach them.

### The longer version

1. **Start with C1** — complete the scaffold. Move the agents bridge
   from platform. Get CI green. Register in parent BOM.

2. **Read `STRATEGY.md`** — understand the positioning. Every module
   you build must be complementary to LC4j, not competitive. If you're
   unsure whether a module crosses the line, check the expansion policy.

3. **Work through C2–C5** (Phase 1 core) — audit, tenancy, governance,
   hybrid-search. The Phase 1 spec has class-level design for each.
   Each module follows the pattern: implement LC4j SPI in `-core`,
   wire CDI in bare module, wire Spring in `-spring`.

4. **Then C6–C9** (Phase 1 extended) — resilience, concurrency budget,
   CRAG gating, retrieval tracking. These pull from engine and
   neocortex. Lighter integration than the core modules.

5. **Phase 2** (C10–C18) — this is the novel stuff. Eidos agent
   identity, Qhorus channels, Work human-in-the-loop. These are
   capabilities LC4j doesn't have at all. Read the CaseHub repos
   (eidos, qhorus, work) to understand the SPIs you're bridging.

6. **Phases 3–4** — detail expands as you get there.

### Key principles to maintain

- **Decorators over replacements** — wrap, don't replace
- **Classpath activation** — adding the dep is the only step
- **Graceful degradation** — missing CaseHub deps → log + no-op, never fail
- **Justified expansion** — new modules need documented justification
- **Both frameworks** — Quarkus and Spring for every module

---

## Learning CaseHub

This project requires familiarity with multiple CaseHub repos. The
ARC42STORIES chapters naturally walk you through them:

| Phase | CaseHub repos you'll learn |
|---|---|
| 1 | platform (tenancy, agent-api), ledger (audit), engine (resilience), neocortex (RAG) |
| 2 | eidos (identity), qhorus (channels), work (tasks), blocks (attestation, oversight) |
| 3 | neocortex (CBR memory, SPLADE, corpus) |
| 4 | engine (execution kernel, case lifecycle), blocks (orchestration patterns) |

Each repo has its own `CLAUDE.md`, contributor guide, and ARC42STORIES.
Read the contributor guide for any repo before bridging its SPIs.

---

## Questions / Decisions Still Open

- Phase structure may be revised — treat current ordering as first draft
- Whether to create new modules for Phase 2 capabilities (eidos, qhorus,
  work) or extend existing module groups — TBD when approaching Phase 2
- Parent repo CI dispatch chain — which repos should trigger rebuilds
  of casehub-langchain4j
- Whether to keep the `casehubio/quarkus-langchain4j` fork repo or
  archive it (it's vanilla upstream, no CaseHub integrations)

---

## Contacts

- Mark Proctor — architecture decisions, positioning, LC4j community engagement
- Mario Fusco — LC4j maintainer (external, not yet engaged — Mark will handle)
