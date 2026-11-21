# Consumer Guide — casehub-langchain4j

Enterprise enrichment for LangChain4j applications. Add CaseHub's audit, tenancy, trust and
hybrid retrieval to an existing LC4j app without replacing its providers or stores.

This guide describes what each module does today. Where something needs a line of code or does
not work yet, it says so.

---

## Getting Started

Import the BOM and pick modules. Each group has a Quarkus artifact (bare name) and a Spring Boot
artifact (`-spring`); the `-core` artifacts are pure Java and come in transitively.

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>io.casehub.langchain4j</groupId>
            <artifactId>casehub-langchain4j-bom</artifactId>
            <version>${casehub.version}</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<dependencies>
    <!-- Quarkus -->
    <dependency>
        <groupId>io.casehub.langchain4j</groupId>
        <artifactId>casehub-langchain4j-audit</artifactId>
    </dependency>
    <!-- Spring Boot: casehub-langchain4j-audit-spring -->
</dependencies>
```

### What is automatic and what is not

| Module | Automatic on the classpath | Needs one line from you |
|---|---|---|
| audit | Audit entry for every LLM call of models the framework builds (quarkus-langchain4j and the LangChain4j Spring starters attach `ChatModelListener` beans) | Attach `CasehubAgentListener` to your root agent; for a model you build yourself, add the `CasehubChatModelListener` bean to its `.listeners(...)` |
| tenancy | `ChatMemoryStore`, `EmbeddingStore`, `ContentRetriever` beans become tenant-isolated | — |
| governance | Replaces the audit agent listener with one that also records trust | Attach the listener to your root agent (same line as audit) |
| hybrid-search | The bean `casehubHybridRetriever` exists | Inject it by name and set the corpora property |
| crag | Your `ContentRetriever` bean is graded and filtered, when a `RelevanceEvaluator` bean exists | — |

LangChain4j has no global registry for agent listeners, so the agent listener must be attached to
an agent. It is inherited by all sub-agents, so attach it once, at the root:

```java
// Plain LangChain4j
AgenticServices.agentBuilder(MyAgent.class).chatModel(model).listener(casehubAgentListener).build();

// quarkus-langchain4j: a static supplier on the agent interface
@AgentListenerSupplier
static AgentListener listener() {
    return Arc.container().instance(CasehubAgentListener.class).get();
}
```

---

## Module Reference

### audit — audit trail for LLM calls and agents

**What you get**

- One entry per LLM call: `AI_CHAT_COMPLETION` with model, input/output/total tokens, latency and
  finish reason, or `AI_CHAT_ERROR` with the error class.
- For agents, a causal chain: `AGENT_INVOCATION_STARTED` → `AGENT_INVOCATION_COMPLETED` or
  `AGENT_INVOCATION_FAILED`, and `AGENT_TOOL_EXECUTED` for tool calls. Each entry points at the
  entry that caused it; a sub-agent's STARTED entry points at its parent's.
- Entries are written to the CaseHub ledger (`LedgerAppender`) when a `LedgerAppender` bean and a
  `CurrentPrincipal` bean exist. Otherwise they are logged at INFO. A startup log line says which.

**What is never recorded:** prompt text, completion text, error messages (provider errors often quote either), stack traces. Errors are recorded by class only.

**Good to know**

- An audit failure never breaks the LLM or agent call; it is logged at WARN and the entry goes to
  the log instead of the ledger.
- Without a tenant (`CurrentPrincipal.tenancyId()` blank) an entry is logged, not written to the
  ledger — the ledger is never written without a tenant.
- Lineage state is dropped as soon as no invocation is open, and on suspension.
- Lineage assumes agents sharing one memory ID nest. Agents running **in parallel** under the same
  memory ID may be chained to a sibling instead of their true parent.
- Not included: cost estimates (there is no pricing source) and the EU AI Act
  `ComplianceSupplement` (the ledger's append API has no slot for it yet).
- To write entries somewhere else, define your own `AuditEntryWriter` bean; it replaces the default.

**Artifacts:** `casehub-langchain4j-audit` (Quarkus), `casehub-langchain4j-audit-spring` (Spring Boot)

### tenancy — tenant isolation for stores and retrievers

**What it wraps:** the application's own `ChatMemoryStore`, `EmbeddingStore<TextSegment>` and
`ContentRetriever` beans. Your implementation (Redis, PostgreSQL, Qdrant, in-memory) stays inside
the decorator.

**What you get**

- `ChatMemoryStore`: the delegate sees the memory ID `<tenant>::<memoryId>`, so two tenants using
  the same memory ID never share a conversation.
- `EmbeddingStore`: every stored segment is tagged with the metadata key `casehub_tenant_id`
  (overwriting any value the caller supplied); every search and every filter-based removal is
  restricted to the current tenant.
- `ContentRetriever`: retrieved content not tagged with the current tenant is dropped — including
  untagged content.

The tenant comes from `CurrentPrincipal.tenancyId()` on every call.

**Fails closed**

| Situation | Behaviour |
|---|---|
| No `CurrentPrincipal` bean at all | Decorators pass through unchanged, and a WARN is logged for every wrapped store — this is the one case that does **not** fail closed |
| Principal present, tenant null or blank | `TenantContextMissingException` — no unscoped access |
| Tenant contains `:` | `TenantContextMissingException` (it would make the scoped memory ID ambiguous) |
| `isCrossTenantAdmin()` is true | No bypass — still scoped to the principal's tenant |

**Limits you should know**

- Embeddings added without a `TextSegment` (`add(Embedding)`, `add(String, Embedding)`,
  `addAll(List<Embedding>)`) cannot carry a tenant tag and throw `UnsupportedOperationException`
  while a tenant is active.
- `remove(String id)` and `removeAll(Collection<String> ids)` throw while a tenant is active: an
  embedding ID carries no tenant. Use `removeAll(Filter)`, which is tenant-scoped.
- `addAll(ids, embeddings, segments)` with IDs you choose is an upsert in most stores: an ID another
  tenant already used replaces that tenant's entry. On a shared store let the store generate IDs.
- The delegate `ChatMemoryStore` receives a `String` memory ID (`<tenant>::<memoryId>`), even when
  your code passes a `Long` or `UUID`.
- Content stored before the module was added has no tenant tag and becomes invisible once a tenant
  is active.
- The delegate store must support metadata filtering.
- Spring: a wrapped bean can only be injected by its interface, not by its concrete class.
- Quarkus: the application's own bean stays reachable through its qualifier (for example
  `@Named`), and injecting it by its concrete class bypasses the decorator. If several beans of a type could be the unqualified one, the module refuses to guess
  and fails on first use — wrap each with its `TenantIsolating*` decorator yourself.

**Artifacts:** `casehub-langchain4j-tenancy` (Quarkus), `casehub-langchain4j-tenancy-spring` (Spring Boot)

### governance — trust on top of the audit lineage

**What you get**

- Everything the audit agent listener records — governance replaces that listener rather than
  running beside it, so there is one lineage.
- The agent's current trust score on its STARTED entry (`trustScore`), read from the ledger's
  `TrustScoreSource`.
- An outcome for every invocation, recorded through the ledger's `OutcomeRecorder`: `SOUND` when
  the invocation completes, `FLAGGED` when it fails. Agents are identified as `agent:<name>`.

Trust is active when `TrustScoreSource` and `OutcomeRecorder` beans exist; otherwise the listener
records lineage only. Recording an outcome also needs a tenant. Outcomes name the attestor
`system:casehub-langchain4j`, so they do not depend on the ledger's default-attestor setting.

**Not available: an approval gate.** LangChain4j catches every exception thrown by an agent
listener and runs the agent anyway, so a listener can observe an invocation but cannot stop it.
This module therefore does not block agents pending human approval, and it does not route between
agents by trust score — it records the score and the outcome.

**Artifacts:** `casehub-langchain4j-governance` (Quarkus), `casehub-langchain4j-governance-spring` (Spring Boot)

### hybrid-search — neocortex hybrid retrieval as a `ContentRetriever`

**What it provides:** a `ContentRetriever` backed by neocortex's pipeline — SPLADE sparse
embeddings, dense embeddings, Reciprocal Rank Fusion and optional reranking, all configured in
neocortex. It exists because LangChain4j has acknowledged the gap
([langchain4j#4087](https://github.com/langchain4j/langchain4j/issues/4087)).

**Opt-in by design.** Hybrid search replaces a retriever, so the bean never competes with your
own: an unqualified `ContentRetriever` injection is unaffected.

```java
// Quarkus
@Inject @HybridSearch ContentRetriever retriever;            // or @Named("casehubHybridRetriever")

// Spring Boot
@Autowired @Qualifier("casehubHybridRetriever") ContentRetriever retriever;
```

Each query searches the configured corpora of the **current tenant**. Results carry the relevance
score (`ContentMetadata.SCORE`) and the segment metadata `source_document_id`, `relevance_grade`
and `casehub_tenant_id`.

**Needs:** a neocortex `CaseRetriever` bean, the corpora property, and a `CurrentPrincipal` to
supply the tenant. A missing piece is reported on first use, not at startup.

**Artifacts:** `casehub-langchain4j-hybrid-search` (Quarkus), `casehub-langchain4j-hybrid-search-spring` (Spring Boot)

---

### crag — Corrective RAG quality filter for any retriever

**What it wraps:** your `ContentRetriever` bean — pgvector, Qdrant used directly, web search, SQL,
anything.

**What you get:** before the model sees a retrieval, every content is graded CORRECT / AMBIGUOUS /
INCORRECT by neocortex's `RelevanceEvaluator`:

- INCORRECT content is removed — an empty list is a valid answer when nothing is relevant;
- CORRECT content comes before AMBIGUOUS, in the retriever's order within each grade;
- each surviving content carries `relevance_grade` in its segment metadata and, when the evaluator
  gives a finite score, `ContentMetadata.RERANKED_SCORE`;
- each retrieval publishes a `CragRetrievalQuality` event (a CDI event in Quarkus, an application
  event in Spring) with the counts per grade.

**You bring the evaluator.** Add neocortex's `casehub-neocortex-rag-crossencoder` or define your own
`RelevanceEvaluator` bean — exactly one; several fail with a clear error. Thresholds belong to the
evaluator and must be calibrated for its model. The cross-encoder evaluator reads the content
text and works with any retriever. The ColBERT evaluator grades only from the retriever's
`ContentMetadata.SCORE`, so it is meaningful only when that score is a ColBERT score; content
without a score is sent with a NaN score and comes back AMBIGUOUS (kept).

| Situation | Behaviour |
|---|---|
| No `RelevanceEvaluator` bean | Retrievers are not wrapped; a WARN (Spring: per retriever bean at startup; Quarkus: when the retriever is first used) |
| Several `RelevanceEvaluator` beans | Clear error (Spring: at startup; Quarkus: when the retriever is first used) |
| Every content already carries CORRECT or AMBIGUOUS (e.g. hybrid-search behind neocortex's CRAG) | Returned untouched, not graded twice |
| The evaluator fails or returns a malformed answer | Content returned **unfiltered**, each marked `relevance_grade = UNGRADED`; a WARN; the quality event has `evaluated = false` |

**No search expansion.** neocortex's own CRAG asks for more results when too few survive;
LangChain4j's `ContentRetriever` has no way to ask for more, so this module only filters and orders.

**Artifacts:** `casehub-langchain4j-crag` (Quarkus), `casehub-langchain4j-crag-spring` (Spring Boot)

## Configuration Reference

| Property | Default | Description |
|---|---|---|
| `casehub.langchain4j.governance.outcome-confidence` | `0.7` | Weight of one agent invocation as trust evidence, in `(0.0, 1.0]`. An invalid value fails startup in Spring, and when the listener is first looked up in Quarkus. |
| `casehub.langchain4j.hybrid-search.corpora` | — (required to use the retriever) | Comma-separated neocortex corpus names |
| `casehub.langchain4j.hybrid-search.max-results` | `10` | Most contents one retrieval returns |

audit and tenancy have no configuration.

Quarkus only: the audit and governance modules inject the ledger and identity beans they use
(`LedgerAppender`, `CurrentPrincipal`, `TrustScoreSource`, `OutcomeRecorder`), so ArC never removes
them as unused — no `quarkus.arc.unremovable-types` entries are needed. This makes
`casehub-ledger-api` and `casehub-platform-api` required dependencies of those two Quarkus modules.

---

## Graceful Degradation

A missing CaseHub dependency never fails startup.

| Module | With CaseHub ledger / identity | Without |
|---|---|---|
| audit | Ledger entries | Logged at INFO |
| tenancy | Tenant-isolated stores | Passes through unchanged (no `CurrentPrincipal` bean) |
| governance | Lineage + trust score + outcomes | Lineage only |
| hybrid-search | Hybrid retrieval | Clear error on first use — it is an implementation, not a decorator |
| crag | Graded, filtered retrieval (needs a `RelevanceEvaluator`) | Retrievers left unwrapped, with a WARN |

---

## Combining Modules

- **audit + governance:** governance replaces the audit agent listener; attach one listener.
- **tenancy + hybrid-search:** hybrid results are tagged with the tenant, so the tenancy content
  filter accepts them.
- **tenancy + crag:** CRAG wraps the tenant filter — CRAG(Tenancy(your retriever)) — so other
  tenants' content never reaches the evaluator.
- **Your own `@Alternative` `ContentRetriever` (Quarkus):** keep its priority below 100. tenancy
  wraps at 100 and crag at 200; an application alternative above either is injected without that
  decorator (both modules log a WARN when that happens).
- **crag + hybrid-search:** in Spring the hybrid bean is wrapped too; results already graded by
  neocortex's CRAG are passed through, not graded again. In Quarkus only the unqualified retriever
  is wrapped.
- **tenancy + your own retriever:** content your retriever returns must carry `casehub_tenant_id`,
  or it is dropped. Content stored through a tenant-isolated `EmbeddingStore` already does.

---

## Examples

Runnable, offline examples live in the `examples` module, each with a test:

- `AuditExample` — one chat call produces one `AI_CHAT_COMPLETION` entry.
- `TenancyExample` — an ordinary `ChatMemoryStore` producer becomes multi-tenant; two tenants use
  the same conversation ID and never see each other.

```bash
mvn test -pl examples -am
```
