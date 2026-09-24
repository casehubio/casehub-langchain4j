# CLAUDE.md

**Name:** casehub-langchain4j

## Project Type

**Type:** java
**Stage:** pre-release

---

## What This Is

Enterprise enrichment for LangChain4j. Adds audit, tenancy, and governance to existing
langchain4j applications without replacing their chosen providers or stores.

Three decorators (wrap existing LC4j beans) and one implementation (hybrid search, filling
LC4j's acknowledged gap #4087).

## Design Specs

Full design documentation is in the workspace:
- `wksp/specs/casehub-langchain4j/STRATEGY.md` — positioning and expansion policy
- `wksp/specs/casehub-langchain4j/decisions.md` — 8 design decisions
- `wksp/specs/casehub-langchain4j/2026-09-24-casehub-langchain4j-design.md` — architecture overview
- `wksp/specs/casehub-langchain4j/2026-09-24-phase-1-enterprise-concerns.md` — Phase 1 implementation spec
- `wksp/specs/casehub-langchain4j/2026-09-24-phase-2-enterprise-implementations.md` — Phase 2 spec
- `wksp/specs/casehub-langchain4j/2026-09-24-phase-3-application-showcase.md` — Phase 3 spec

---

## Work Tracking

**Issue tracking:** enabled

All implementation work must be linked to a GitHub issue:
- Before starting implementation, create an epic + child issues (or confirm an existing issue)
- All commits reference an issue: `Refs #N` (work in progress) or `Closes #N` (completes the issue)
- When staged changes span multiple concerns, split into separate commits with separate issue references

**Repository:** casehubio/casehub-langchain4j

---

## Build & Test

```bash
mvn install -DskipTests -q                   # install all modules
mvn clean test                               # full test suite
mvn clean test -pl audit-core                # single module
```

Quarkus version: `3.32.2`
LangChain4j version: `1.14.1`
Spring Boot version: `4.1.0`

---

## Module Structure

| Group | Core | Quarkus | Spring |
|---|---|---|---|
| audit | audit-core | audit | audit-spring |
| tenancy | tenancy-core | tenancy | tenancy-spring |
| governance | governance-core | governance | governance-spring |
| hybrid-search | hybrid-search-core | hybrid-search | hybrid-search-spring |
| agents | agents-core | agents | agents-spring |

Plus: `bom` (BOM), `examples` (not published).

---

## IntelliJ MCP Tools

Two IntelliJ MCP servers are available (`mcp__intellij__*` and `mcp__intellij-index__*`).
Before using Bash tools, check whether the operation can be performed via IntelliJ — it is
often more correct, faster, and less error-prone.

Use `mcp__intellij-index__*` for code navigation (it supports auto-opening projects via
`project_path`). Never ask the user to open a project manually.
