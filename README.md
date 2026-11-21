# casehub-langchain4j

Enterprise enrichment for LangChain4j — audit, tenancy, governance.

CaseHub is additive. A langchain4j application works without CaseHub. Each module adds an
enterprise concern that langchain4j deliberately does not handle.

## Modules

| Module | What it adds | Developer effort |
|---|---|---|
| **audit** | Ledger entries for every LLM call; causal lineage for agents | Add dependency (attach one listener for agents) |
| **tenancy** | Tenant isolation on any store or retriever | Add dependency |
| **governance** | Trust scores and outcomes on the agent lineage; human approval gate for risky tool calls | Add dependency, attach the listener, wrap the agent's tools |
| **hybrid-search** | SPLADE + dense + RRF hybrid retrieval ([langchain4j#4087](https://github.com/langchain4j/langchain4j/issues/4087)) | Inject one named bean |
| **crag** | Corrective RAG: grades retrieved content, drops irrelevant chunks before the LLM sees them | Add dependency + a `RelevanceEvaluator` |

Each module group has three variants: `-core` (pure Java), bare name (Quarkus CDI), `-spring` (Spring auto-config).

See the [consumer guide](docs/guides/consumer-guide.md) for exactly what each module does and does not do.

## Strategy

See [STRATEGY.md](https://github.com/casehubio/engine/tree/main/docs/specs/casehub-langchain4j/STRATEGY.md) for the full positioning rationale.

## Build

```bash
mvn install -DskipTests -q
mvn clean test
```
