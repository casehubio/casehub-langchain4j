# casehub-langchain4j — Vision and Roadmap

## What This Is

LangChain4j is the standard Java library for building LLM-powered
applications. It handles model providers, memory, RAG, tool calling, and
agentic orchestration patterns. It does this well.

What it doesn't handle — by design — is what happens when those
applications need to operate in enterprise environments: cryptographic
audit trails, multi-tenant data isolation, human oversight of agent
decisions, agent identity and reputation, structured inter-agent
communication, and compliance evidence.

`casehub-langchain4j` adds these capabilities to LangChain4j
applications. It wraps existing LangChain4j components with enterprise
concerns — it does not replace them. A developer keeps their chosen
providers, stores, and orchestration patterns. CaseHub enriches them.

## Why a LangChain4j Developer Needs This

These are real problems that surface when LC4j applications move from
proof-of-concept to production deployment. Each one has blocked or
delayed enterprise adoption.

### "Our auditor asked for evidence that the AI made this decision."

An LC4j application calls `chatModel.chat()` and gets a response. There
is no record of what was sent, what was returned, which model version was
used, or how much it cost — unless the developer builds logging
themselves. Under EU AI Act Art.12, high-risk AI systems must maintain
logs "to enable the tracing of the AI system's operations." OTel traces
show that a call happened. They do not prove what the model received and
returned, and they are not tamper-evident.

**casehub-langchain4j-audit** adds a `ChatModelListener` that produces
cryptographically signed `CaseLedgerEntry` records for every LLM
interaction. Merkle Mountain Range inclusion proofs. Ed25519 checkpoint
signing. The developer adds one dependency. Their existing ChatModel —
Ollama, OpenAI, Anthropic, whatever — gains compliance-grade evidence.

### "Customer A's documents appeared in Customer B's search results."

LC4j's `EmbeddingStore.search()` returns the nearest vectors — with no
awareness of which tenant owns them. `ContentRetriever.retrieve()` has no
tenant filter. In a multi-tenant SaaS deployment, embedding and retrieval
operations must be scoped per tenant. This is not trivial for vector
stores — it requires metadata filtering on every search, tenant-tagged
ingestion on every add, and consistent scoping across the RAG pipeline.
`ChatMemoryStore` is simpler (prefix the memoryId), but embedding stores
and content retrievers are where cross-tenant leakage actually happens.

**casehub-langchain4j-tenancy** wraps any `EmbeddingStore`,
`ContentRetriever`, or `ChatMemoryStore` with a decorator that scopes
every operation to `CurrentPrincipal.tenancyId()`. The developer's
existing Qdrant, PostgreSQL, or Redis store is preserved inside the
decorator. Cross-tenant access is structurally impossible through the
wrapped interface. Lightweight — depends only on `casehub-platform-api`
(a zero-dep Java SPI module).

### "The agent approved a €2M transaction without human review."

LC4j's `@SupervisorAgent` plans actions and executes them. There is no
checkpoint between "the LLM decided to do this" and "it happened." In
financial services, healthcare, and government, certain actions require
human approval before execution. An agent that autonomously approves
transactions, disables accounts, or prescribes medication is a liability.

**casehub-langchain4j-governance** implements `AgentListener` to
intercept agent invocations. Before a sensitive action executes, the
governance layer checks an oversight gate. If approval is required:
execution pauses, a `WorkItem` is created for the right approver group,
and execution resumes only after a human says yes. The LC4j agent code
does not change.

### "We have 12 agents that can handle this task. Which one should we pick?"

LC4j's supervisor mode picks the next agent by asking the LLM. There is
no historical data about which agent performs well on which type of task.
There is no feedback loop from outcomes to routing. Every invocation is
a fresh guess.

**casehub-langchain4j-governance** adds trust routing: Bayesian Beta
trust scores evolve from attestation events and outcome history. Agents
that have historically produced good results for similar tasks get
selected. Agents whose outputs have been rejected by reviewers get
deprioritised. The LLM still plans — but its choices are informed by
evidence.

### "Our agent is a ChatModel and a prompt string. It has no identity."

LC4j agents are defined by their system prompt. They have no persistent
identity, no track record, no capabilities that can be queried, and no
reputation. Two agents that happen to have the same prompt are
indistinguishable.

**casehub-langchain4j-eidos** gives agents structured identity:
dynamic system prompt rendering from semantic context (role, capability,
goals, constraints), capability declarations with health probing
(agents report whether their skills are currently operable), and
subsumption-based capability matching (select agents by what they can
actually do, not by name). An agent's reputation evolves from outcomes
across sessions. Personality composition — weighted disposition axes
and archetype profiles — is available for applications that need
differentiated agent behaviour (proven in Wacky Manor with ~8
distinguishable personalities in practice). This is not prompt
engineering — it is agent architecture.

### "Our agents communicate via method calls. We can't audit what they said."

LC4j's multi-agent patterns (supervisor, sequence, parallel) coordinate
via direct method invocations and shared `AgenticScope` state. There is
no record of what agent A said to agent B, no access control on who can
read shared state, and no structured protocol for multi-agent
deliberation.

**casehub-langchain4j-qhorus** gives agents typed communication channels
with five delivery semantics, access controls, rate limiting, and
delivery tracking. Messages are `MessageLedgerEntry` records — auditable,
tamper-evident. The normative commitment layer (speech-act theory) means
a promise creates an enforceable obligation and violations are detected.
This is structured inter-agent communication, not shared mutable state.

### "An agent tool call failed and the whole pipeline crashed."

LC4j has no resilience layer. A failed tool call or a model timeout is
a hard stop. A hung agent blocks forever. There is no retry, no dead
letter queue, no circuit breaker, no cost budget.

Generic resilience libraries (Resilience4j, MicroProfile Fault
Tolerance, Quarkus `@Retry`/`@Timeout`) handle method-level retry and
timeout. They do not handle agent-specific concerns: detecting that an
agent is stuck in a reasoning loop (not just slow), identifying poison
inputs that will fail on every retry, or maintaining a dead letter queue
of failed agent invocations for later inspection and replay.

**casehub-langchain4j-resilience** adds agent-aware resilience:
`PoisonPillDetector` identifies inputs that will always fail.
`WatchdogRecoveryBridge` detects hung agents (not just slow responses —
reasoning loops). `DeadLetterQueue` captures failed invocations for
inspection. `DispatchBudget` limits concurrent invocations for cost
control. These compose with generic resilience — they don't replace it.

## The Four Phases

### Phase 1 — Your LC4j App Is Now Enterprise-Class

Add a dependency. No code changes. Your existing application gains:

- **Audit** — every LLM call produces a tamper-evident ledger entry with
  Merkle proof inclusion, EU AI Act compliance supplements, token usage,
  and cost tracking. Implemented via `ChatModelListener` — wraps any
  ChatModel transparently.

- **Tenancy** — your ChatMemoryStore, EmbeddingStore, and ContentRetriever
  become multi-tenant. Decorators scope every operation to the current
  tenant. Works with whatever backing implementation you already chose.

- **Governance** — agent invocations gain causal lineage (who called what
  and why), oversight gates (human approval before sensitive actions), and
  trust routing (agents with better track records get selected first).

- **Hybrid Search** — replace vector-only retrieval with SPLADE sparse +
  dense embeddings + reciprocal rank fusion + cross-encoder reranking.
  26–31% NDCG improvement. Fills an acknowledged gap in LC4j (#4087).

- **Resilience** — failed agent tool calls go to a dead letter queue and
  retry instead of crashing. Hung agents get detected and cancelled.
  Concurrent invocations are budget-limited for cost control.

- **Quality Gating** — corrective RAG automatically discards low-relevance
  retrieved chunks before the LLM sees them, reducing hallucination.

### Phase 2 — Your LC4j Agents Are Real Distributed Agents

This is where LC4j agents gain capabilities that don't exist anywhere
else in the Java ecosystem:

- **Agent Identity (Eidos)** — your agent goes from a hardcoded system
  prompt to a dynamically rendered identity with personality (48
  archetypes from Hartwell & Chen), disposition axes, goal narratives,
  and epistemic confidence. The same agent code behaves differently
  depending on context.

- **Agent Reputation (Eidos + Ledger)** — other agents and humans
  rate your agent's outputs. Bayesian Beta trust scores evolve from
  verifiable peer review. Your agent's reputation is earned, not
  declared.

- **Typed Communication (Qhorus)** — your agents get structured messaging
  channels with five delivery semantics, access controls, rate limiting,
  and delivery tracking. Not method calls — real inter-agent communication
  with auditable history.

- **Normative Commitments (Qhorus)** — agent messages carry deontic
  obligations. A promise creates an enforceable commitment. A request
  creates an expectation. Violations are detected and recorded. This is
  speech-act theory applied to multi-agent systems.

- **Human-in-the-Loop (Work)** — your agent creates a WorkItem for human
  review. 11-status lifecycle, SLA breach policies with escalation chains,
  M-of-N group completion, delegation. The agent pauses; a human decides;
  the agent resumes.

- **Advanced RAG (Neocortex)** — HyDE query expansion, step-back
  reformulation, NLI hallucination detection via local ONNX inference,
  structured attestation records for every agent decision.

### Phase 3 — Enterprise-Grade Infrastructure

For deployments that need deeper capability backing:

- **Case-Based Reasoning Memory** — agents learn from past cases.
  Similarity-weighted retrieval, outcome feedback loops, plan adaptation.
  A different memory paradigm from ChatMemoryStore — not competing, a
  different category.

- **SPLADE Sparse Embeddings** — learned term expansion for domain-specific
  vocabulary. The only JVM implementation available.

- **Corpus Management** — enterprise document lifecycle with versioning,
  provenance tracking, and incremental re-indexing. Beyond LC4j's basic
  Tika parsing.

### Phase 4 — Full Platform Orchestration

For teams ready to leverage CaseHub's complete orchestration capabilities
alongside their LC4j agents:

- **Case Lifecycle** — agents operate within managed cases with goals,
  milestones, and stage gating. The execution kernel handles routing,
  binding evaluation, and loop control.

- **Orchestration Patterns** — annotation-driven supervisor, sequence,
  parallel, debate, and voting patterns. CaseHub's patterns compose with
  governance meta-annotations — oversight gates, trust routing, and
  attestation apply uniformly.

- **DAG Execution** — dependency-graph-aware parallel dispatch with
  ANY_OF/ALL_OF join types and contingency nodes. Beyond LC4j's flat
  parallel.

- **Multi-Agent Protocols** — structured conversation, negotiation,
  judgment, and coalition formation. Turn-based, policy-driven, with
  consensus and termination conditions.

## Design Principles

**Additive, not competitive.** We wrap LangChain4j components — we don't
replace them. A developer keeps their ChatModel, their memory store, their
orchestration pattern. CaseHub adds enterprise concerns on top.

**Classpath activation.** Add a dependency. No configuration. Enterprise
capabilities activate automatically. If CaseHub dependencies are absent,
modules degrade gracefully — log a warning, never fail.

**Justified expansion.** Every module beyond the Phase 1 core must
document why it exists: (1) LangChain4j acknowledged the gap, (2) it's a
different category not competing with an LC4j capability, or (3) a
customer requested it. This is a public commitment.

**Both frameworks.** Every module ships with Quarkus CDI and Spring
auto-configuration from day one.

## Dependency Transparency

Not all modules are equal in weight. The dependency footprint per module:

| Module | Transitive CaseHub deps | Weight |
|---|---|---|
| audit | casehub-ledger-api, casehub-platform-api | Light — two API JARs |
| tenancy | casehub-platform-api | Minimal — one API JAR |
| resilience | casehub-engine-common-core | Light — engine utilities only |
| governance | casehub-platform-agent-api, casehub-ledger-api, casehub-engine-api (optional) | Medium — oversight gates need engine |
| eidos | casehub-eidos-api | Medium — identity model |
| qhorus | casehub-qhorus-api | Medium — channel infrastructure |
| work (human-in-the-loop) | casehub-work-api | Medium — WorkItem lifecycle |

Phase 1 modules (audit, tenancy, resilience) are genuinely light — API
JARs with no runtime infrastructure requirements. Phase 2 modules
(eidos, qhorus, work) are heavier — they bring real capabilities that
require real backing services. We are upfront about this because
"add one dependency" should mean the developer knows what they're
getting.

Phase 4 (engine orchestration, blocks patterns) is a platform adoption
decision, not a drop-in enrichment. We don't pretend otherwise. The path
from Phase 1 to Phase 4 is gradual and each step is optional — but
Phase 4 is choosing CaseHub as your orchestration layer, and we're
honest about that.

---

## Architecture

```
casehub-langchain4j
  → langchain4j-core         Your existing LC4j code
  → casehub-platform         Identity, tenancy, agent SPIs
  → casehub-ledger           Audit trail, trust scoring
  → casehub-neocortex        Cognition — RAG, memory, inference
  → casehub-eidos            Agent identity, personality
  → casehub-qhorus           Channels, deliberation
  → casehub-work             Human tasks, SLA, escalation
  → casehub-engine           Case lifecycle, orchestration
  → casehub-blocks           Annotation patterns, governance
```

No upstream CaseHub repo depends on this repo. The dependency arrow
points one way. CaseHub enriches LangChain4j — LangChain4j doesn't need
to know CaseHub exists.

## Getting Started

```xml
<!-- Phase 1: Audit every LLM call -->
<dependency>
    <groupId>io.casehub.langchain4j</groupId>
    <artifactId>casehub-langchain4j-audit</artifactId>
</dependency>

<!-- Phase 1: Multi-tenant memory -->
<dependency>
    <groupId>io.casehub.langchain4j</groupId>
    <artifactId>casehub-langchain4j-tenancy</artifactId>
</dependency>

<!-- Phase 2: Agent identity -->
<dependency>
    <groupId>io.casehub.langchain4j</groupId>
    <artifactId>casehub-langchain4j-eidos</artifactId>
</dependency>
```

No code changes. No configuration. Your existing LC4j application gains
enterprise capabilities.
