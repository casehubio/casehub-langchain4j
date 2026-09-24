# Consumer Guide — casehub-langchain4j

Enterprise enrichment for LangChain4j applications. Add CaseHub's audit, tenancy, and governance capabilities to your existing LC4j app — without changing your code.

---

## Getting Started

Add one dependency. No configuration. No code changes.

```xml
<!-- Quarkus: audit module -->
<dependency>
    <groupId>io.casehub.langchain4j</groupId>
    <artifactId>casehub-langchain4j-audit</artifactId>
</dependency>

<!-- Spring Boot: audit module -->
<dependency>
    <groupId>io.casehub.langchain4j</groupId>
    <artifactId>casehub-langchain4j-audit-spring</artifactId>
</dependency>

<!-- Or import the BOM and pick what you need -->
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
```

Your existing LC4j providers, stores, and patterns continue to work unchanged. CaseHub enrichment activates automatically.

---

## Module Reference

### audit — Tamper-evident audit trail

**What it wraps:** Any `ChatModel` (via `ChatModelListener`) and any LC4j agent (via `AgentListener`)

**What you get:**
- Every LLM call → tamper-evident `CaseLedgerEntry` with Merkle proof
- Every agent invocation → `CaseLedgerEntry` with causal chain (`causedByEntryId`)
- Token usage, cost estimate, latency, model name per call
- EU AI Act Art.12 `ComplianceSupplement` on every AI decision
- Ed25519 tlog-checkpoint signing

**Developer effort:** Add dependency. Done.

**Artifacts:**
- `casehub-langchain4j-audit` (Quarkus)
- `casehub-langchain4j-audit-spring` (Spring Boot)

---

### tenancy — Multi-tenant isolation

**What it wraps:** Any `ChatMemoryStore`, `EmbeddingStore`, or `ContentRetriever`

**What you get:**
- Tenant-isolated chat memories — Tenant A cannot read Tenant B's messages
- Tenant-scoped embeddings — search results filtered to current tenant
- Tenant-scoped retrieval — content retrieval respects tenant boundaries
- Works with ANY backing implementation (Redis, PostgreSQL, Qdrant, in-memory — whatever you already use)

**Developer effort:** Add dependency. Done. Tenant ID is sourced from `CurrentPrincipal.tenancyId()` (casehub-platform-api).

**Artifacts:**
- `casehub-langchain4j-tenancy` (Quarkus)
- `casehub-langchain4j-tenancy-spring` (Spring Boot)

---

### governance — Agent oversight and trust

**What it wraps:** Any LC4j agent pattern (`@SupervisorAgent`, `@SequenceAgent`, `@ParallelAgent`, etc.)

**What you get:**
- **Causal lineage** — every agent invocation chain is traceable in the ledger
- **Oversight gates** — sensitive actions pause for human approval before executing
- **Trust routing** — agents with good outcome history are preferred; agents with rejected outputs are deprioritised
- **Tool call audit** — every tool execution within an agent is recorded in the lineage

**Developer effort:** Add dependency. Done. Governance operates at the agent invocation boundary — your LC4j patterns are untouched.

**Artifacts:**
- `casehub-langchain4j-governance` (Quarkus)
- `casehub-langchain4j-governance-spring` (Spring Boot)

**Note:** Oversight gates require casehub-engine on the classpath. Without engine, governance provides lineage only (still valuable for audit).

---

### hybrid-search — Enterprise RAG

**What it provides:** A `ContentRetriever` implementation with hybrid search

**What you get:**
- SPLADE sparse embeddings (learned term expansion — "prepayment" finds "early closure", "advance payment")
- Dense vector embeddings (any LC4j `EmbeddingModel`)
- Reciprocal Rank Fusion (Qdrant server-side)
- Optional cross-encoder reranking (ONNX, in-process)
- 26–31% NDCG improvement over dense-only retrieval

**Developer effort:** Swap one bean — replace `EmbeddingStoreContentRetriever` with `CasehubHybridContentRetriever`. This is the only module that replaces rather than decorates — justified by LC4j's acknowledged gap ([#4087](https://github.com/langchain4j/langchain4j/issues/4087)).

**Artifacts:**
- `casehub-langchain4j-hybrid-search` (Quarkus)
- `casehub-langchain4j-hybrid-search-spring` (Spring Boot)

---

### agents — ChatModel ↔ AgentProvider bridge

**What it provides:** Bidirectional bridge between LC4j's ChatModel and CaseHub's AgentProvider

**What you get:**
- Any LC4j `ChatModel` works as a CaseHub `AgentProvider`
- Any CaseHub `AgentProvider` works as a LC4j `ChatModel`
- Session management, timeout enforcement, concurrent query protection

**Developer effort:** Add dependency. The bridge activates automatically based on what's on the classpath.

**Artifacts:**
- `casehub-langchain4j-agents` (Quarkus)
- `casehub-langchain4j-agents-spring` (Spring Boot)

---

## Configuration Reference

All configuration is optional. Modules work with zero configuration.

### audit

| Property | Default | Description |
|---|---|---|
| `casehub.langchain4j.audit.cost-tracking.enabled` | `true` | Include cost estimates in ledger entries |
| `casehub.langchain4j.audit.compliance-supplement.enabled` | `true` | Attach EU AI Act ComplianceSupplement |

### tenancy

| Property | Default | Description |
|---|---|---|
| `casehub.langchain4j.tenancy.prefix-separator` | `::` | Separator between tenant ID and memory ID |
| `casehub.langchain4j.tenancy.metadata-key` | `tenantId` | Metadata key used for tenant tagging on embeddings |

### governance

| Property | Default | Description |
|---|---|---|
| `casehub.langchain4j.governance.oversight.timeout` | `PT5M` | Maximum wait time for human approval |
| `casehub.langchain4j.governance.trust.enabled` | `true` | Enable trust-weighted agent routing |
| `casehub.langchain4j.governance.lineage.enabled` | `true` | Enable causal lineage recording |

### hybrid-search

| Property | Default | Description |
|---|---|---|
| `casehub.langchain4j.hybrid-search.reranking.enabled` | `true` | Enable cross-encoder reranking |
| `casehub.langchain4j.hybrid-search.reranking.top-n` | `50` | Number of candidates to rerank |
| `casehub.langchain4j.hybrid-search.splade.threshold` | `0.01` | SPLADE sparsity threshold |

### agents

| Property | Default | Description |
|---|---|---|
| `casehub.platform.agent.langchain4j.closeTimeout` | `PT30S` | Session close timeout |
| `casehub.platform.agent.langchain4j.sessionMemoryWindowSize` | `20` | Chat memory window size for multi-turn sessions |

---

## Graceful Degradation

Every module handles missing optional dependencies gracefully. Your application never fails because a CaseHub dependency is absent.

| Module | Full (all deps present) | Reduced (some deps missing) | Passthrough (CaseHub absent) |
|---|---|---|---|
| audit | Cryptographic ledger entries | EventLog entries (no crypto) | slf4j logging |
| tenancy | Tenant-isolated stores | — | Single-tenant passthrough |
| governance | Lineage + oversight + trust | Lineage only (no engine) | slf4j logging |
| hybrid-search | Full hybrid pipeline | — | N/A (this is an implementation, not a decorator) |

---

## Combining Modules

Modules are independent and composable. Use any combination:

```xml
<!-- Audit only -->
<dependency>
    <groupId>io.casehub.langchain4j</groupId>
    <artifactId>casehub-langchain4j-audit</artifactId>
</dependency>

<!-- Audit + Tenancy -->
<dependency>
    <groupId>io.casehub.langchain4j</groupId>
    <artifactId>casehub-langchain4j-audit</artifactId>
</dependency>
<dependency>
    <groupId>io.casehub.langchain4j</groupId>
    <artifactId>casehub-langchain4j-tenancy</artifactId>
</dependency>

<!-- Everything -->
<dependency>
    <groupId>io.casehub.langchain4j</groupId>
    <artifactId>casehub-langchain4j-bom</artifactId>
    <version>${casehub.version}</version>
    <type>pom</type>
    <scope>import</scope>
</dependency>
```

No ordering constraints. No CDI conflicts between modules. Each module adds its concern independently.

---

## Examples

### Audit — add compliance to an existing chatbot

```java
// Your existing code — NO CHANGES NEEDED
@RegisterAiService
public interface MyAssistant {
    String chat(@UserMessage String message);
}

// Just add casehub-langchain4j-audit to your pom.xml.
// Every call to MyAssistant.chat() now produces a
// tamper-evident CaseLedgerEntry with:
//   - model name, token count, cost, latency
//   - EU AI Act ComplianceSupplement
//   - Merkle inclusion proof
```

### Tenancy — make any memory store multi-tenant

```java
// Your existing code — NO CHANGES NEEDED
@ApplicationScoped
public class MyMemoryStore implements ChatMemoryStore {
    // Your Redis/PostgreSQL/whatever implementation
}

// Just add casehub-langchain4j-tenancy to your pom.xml.
// Your MyMemoryStore is automatically wrapped with
// TenantIsolatingChatMemoryStore. Tenant A's messages
// are invisible to Tenant B. Your implementation code
// doesn't change.
```

### Governance — add oversight to a supervisor agent

```java
// Your existing code — NO CHANGES NEEDED
@SupervisorAgent(subAgents = {TriageAgent.class, ContainmentAgent.class})
public interface SecuritySupervisor {
    String investigate(@UserMessage String alert);
}

// Just add casehub-langchain4j-governance to your pom.xml.
// Every agent invocation in the supervisor tree now:
//   - Creates a tamper-evident lineage entry
//   - Checks if human approval is required
//   - Records outcomes for trust scoring
```
