# casehub-langchain4j

Enterprise enrichment for LangChain4j — audit, tenancy, governance.

CaseHub is additive. A langchain4j application works without CaseHub. Each module adds an
enterprise concern that langchain4j deliberately does not handle.

## Modules

| Module | What it adds | Developer effort |
|---|---|---|
| **audit** | Tamper-evident ledger entries for every LLM call | Add dependency |
| **tenancy** | Tenant isolation on any store or retriever | Add dependency |
| **governance** | Oversight gates, trust routing, causal lineage for agents | Add dependency |
| **hybrid-search** | SPLADE + dense + RRF hybrid retrieval ([langchain4j#4087](https://github.com/langchain4j/langchain4j/issues/4087)) | Swap one bean |
| **agents** | ChatModel ↔ AgentProvider bidirectional bridge | — |

Each module group has three variants: `-core` (pure Java), bare name (Quarkus CDI), `-spring` (Spring auto-config).

## Strategy

See [STRATEGY.md](wksp/specs/casehub-langchain4j/STRATEGY.md) for the full positioning rationale.

## Build

```bash
mvn install -DskipTests -q
mvn clean test
```
