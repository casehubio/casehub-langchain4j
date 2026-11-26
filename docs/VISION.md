# casehub-langchain4j — Vision and Roadmap

## What This Is

LangChain4j is the standard Java library for building LLM-powered
applications. It handles model providers, memory, RAG, tool calling, and
agentic orchestration patterns. It does this well.

What it doesn't handle — by design — is what happens when those
applications need to operate in enterprise environments: cryptographic
audit trails, multi-tenant data isolation, human oversight of agent
actions, evidence of which documents a RAG answer used, and compliance
evidence.

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

**casehub-langchain4j-audit** adds a `ChatModelListener` that writes a
record of every LLM interaction to the CaseHub ledger, whose entries are
tamper-evident. The developer adds one dependency. Their existing ChatModel —
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

**casehub-langchain4j-governance** puts a gate in front of the agent's
tool calls. An `AgentListener` cannot block — LangChain4j catches listener
exceptions and runs the agent anyway — so the gate wraps the tool
executors instead. Each call is classified by the application's risk
classifiers; a call that needs approval runs only after a human completes
a casehub-work task for that exact call, once. Approvals work in-process
or against a remote casehub-work over REST.

### "We have 12 agents that can handle this task. Which one should we pick?"

LC4j's supervisor mode picks the next agent by asking the LLM. There is
no historical data about which agent performs well on which type of task.
There is no feedback loop from outcomes to routing. Every invocation is
a fresh guess.

**casehub-langchain4j-governance** records the evidence for this: each
agent's trust score on every invocation, and every outcome (completed or
failed) as a ledger attestation, so Bayesian trust scores evolve from real
history. *Using* those scores to pick the next agent is planned, not yet
available.

### "Which documents did the AI actually use for that answer?"

A RAG pipeline retrieves passages, the model answers, and the passages
are gone. Nobody can say which documents a given answer relied on, which
questions found nothing, or which documents are never retrieved at all.
When the pipeline rewrites the question into several queries, even
counting retrievals goes wrong.

**casehub-langchain4j-tracking** records every question with the
documents the model received, in neocortex's retrieval tracker — once per
question, also when the augmentor expands or rewrites it. Each passage
carries a retrieval ID for feedback, and neocortex's analytics find
zero-hit questions and document impact. **casehub-langchain4j-crag**
goes one step earlier: a cross-encoder grades every retrieved passage
and drops the irrelevant ones before the model reasons over them.

### "Our agents have no identity, and they talk through method calls."

LC4j agents are defined by their system prompt and coordinate through
direct method invocations and shared `AgenticScope` state. There is no
persistent identity or reputation, and no record of what agent A said to
agent B.

CaseHub answers this with eidos (structured agent identity, capability
matching, reputation) and qhorus (typed channels with delivery semantics,
normative commitments). These need the CaseHub platform behind them, so
they are not drop-in modules here: they are part of Phase 4, where your
LC4j agents run inside CaseHub.

## The Four Phases

### Phase 1 — Your LC4j App Is Now Enterprise-Class

Add a dependency. No code changes. Your existing application gains:

- **Audit** — every LLM call produces a ledger entry with model, token
  usage, latency and finish reason; agent invocations produce a causal
  chain. Implemented via `ChatModelListener` — wraps any
  ChatModel transparently.

- **Tenancy** — your ChatMemoryStore, EmbeddingStore, and ContentRetriever
  become multi-tenant. Decorators scope every operation to the current
  tenant. Works with whatever backing implementation you already chose.

- **Governance** — an oversight gate on tool calls (human approval through
  casehub-work), plus trust scores and recorded outcomes on top of the
  causal lineage. Trust-based routing is planned.

- **Hybrid Search** — replace vector-only retrieval with SPLADE sparse +
  dense embeddings + reciprocal rank fusion + cross-encoder reranking.
  26–31% NDCG improvement. Fills an acknowledged gap in LC4j (#4087).

- **Quality Gating** — corrective RAG automatically discards low-relevance
  retrieved chunks before the LLM sees them, reducing hallucination.

- **Retrieval Tracking** — every question is recorded with the documents
  the model received, for feedback and analytics (zero-hit questions,
  document impact). Correct also for query-rewriting pipelines — see the
  [query rewriting recipe](guides/query-rewriting.md).

Two planned Phase 1 modules were dropped: agent resilience (we wait for
upstream Quarkus work instead) and a concurrency budget (a few lines of
application code, no module needed).

### Phase 2 — Folded Into Phases 1 and 4

Phase 2 promised distributed-agent capabilities. Implementation showed a
different shape: human-in-the-loop approval and oversight shipped inside
Phase 1 governance; query expansion is already LangChain4j's
`QueryTransformer` (we document how to track it instead of wrapping it);
NLI hallucination detection proved unreliable on real LLM output; agent
identity, typed channels and peer trust need the CaseHub platform behind
them and moved to Phase 4.

### Phase 3 — Capabilities Nothing Else on the JVM Offers

Modules for LC4j developers that depend on neocortex and platform modules
which run standalone — not on the full CaseHub platform. Planned, in this
order:

- **NLP Template Rendering and Extraction** — your agent writes prose, you
  get structured data. Templates render data into the prompt and extract
  fields from the answer, tolerating natural variation, and re-ask with the
  expected sentence shape when extraction fails. No JSON mode.

- **Agent Simulation** — a `ChatModel` that answers from a versioned
  prompt→response corpus. Capture real traffic during development, replay
  it deterministically in CI. No ad-hoc mocks, no real LLM in tests.

- **Case-Based Reasoning Memory** — agents learn from past cases.
  Similarity-weighted retrieval, outcome feedback loops, plan adaptation.
  A different memory paradigm from ChatMemoryStore — not competing, a
  different category.

- **Governed MCP Playbooks** — a guide, not a module: the
  [distributions](https://github.com/casehubio/distributions) MCP server
  exposes 130+ connector tools and multi-step playbooks to any agent
  runtime, and audit, the oversight gate and tenancy already apply to those
  calls.

The path in: discover the MCP server's tools → add casehub-langchain4j for
enterprise trust on those calls → add RAG quality and the Phase 3 modules
→ adopt the full platform.

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

- **Agent Identity, Channels and Trust** — eidos identity and reputation,
  qhorus typed channels and normative commitments, ledger peer trust —
  as reference architectures for LC4j agents running inside CaseHub.

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
| audit | casehub-ledger-api, casehub-platform-api (both optional) | Light — two API JARs |
| tenancy | casehub-platform-api | Minimal — one API JAR |
| governance | casehub-langchain4j-audit-core; optional casehub-ledger-api, casehub-work-api, casehub-engine-api | Light — the gate needs casehub-work and engine's risk classifiers |
| hybrid-search | casehub-neocortex-rag-api | Light API, but needs neocortex's retrieval pipeline at runtime |
| crag | casehub-neocortex-rag-api | Light — plus a relevance evaluator (cross-encoder) |
| tracking | casehub-neocortex-rag-api | Light — plus a retrieval tracker (neocortex SQLite or your own) |

Phase 1 modules are genuinely light — API JARs, with the backing service
named where one is needed. Phase 3 modules will bring neocortex or
platform runtime modules with them; each will state what it needs. We are
upfront about this because "add one dependency" should mean the developer
knows what they're getting.

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
  → casehub-neocortex        Cognition — RAG, retrieval tracking, memory
  → casehub-work             Approvals for the oversight gate
  → casehub-engine           Risk classification; Phase 4 orchestration
  → casehub-eidos, -qhorus,  Phase 4 — identity, channels,
    -agency                  orchestration patterns
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

<!-- Phase 1: Record which documents each answer used -->
<dependency>
    <groupId>io.casehub.langchain4j</groupId>
    <artifactId>casehub-langchain4j-tracking</artifactId>
</dependency>
```

No code changes. No configuration. Your existing LC4j application gains
enterprise capabilities.
