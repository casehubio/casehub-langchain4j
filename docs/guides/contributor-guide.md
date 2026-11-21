# Contributor Guide — casehub-langchain4j

## Module Architecture

| Module | ArtifactId | Package | Responsibility |
|---|---|---|---|
| `audit-core` | `casehub-langchain4j-audit-core` | `io.casehub.langchain4j.audit` | Pure Java: `CasehubChatModelListener`, `CasehubAgentListener`, `AuditEntryWriter` SPI, `LedgerAuditEntryWriter` → ledger `AuditRecord` |
| `audit` | `casehub-langchain4j-audit` | `io.casehub.langchain4j.audit.quarkus` | CDI `@Produces` for listener beans. Jandex indexed. |
| `audit-spring` | `casehub-langchain4j-audit-spring` | `io.casehub.langchain4j.audit.spring` | Spring `@Configuration @ConditionalOnClass` auto-config |
| `tenancy-core` | `casehub-langchain4j-tenancy-core` | `io.casehub.langchain4j.tenancy` | Pure Java: `TenantIsolatingChatMemoryStore`, `TenantIsolatingEmbeddingStore`, `TenantIsolatingContentRetriever` |
| `tenancy` | `casehub-langchain4j-tenancy` | `io.casehub.langchain4j.tenancy.quarkus` | CDI bean discovery and wrapping |
| `tenancy-spring` | `casehub-langchain4j-tenancy-spring` | `io.casehub.langchain4j.tenancy.spring` | Spring `BeanPostProcessor` wrapping |
| `governance-core` | `casehub-langchain4j-governance-core` | `io.casehub.langchain4j.governance` | Pure Java: `GovernanceAgentListener` (extends `CasehubAgentListener`) → lineage + trust (`TrustPort`, `LedgerTrustPort`); `gate` package: `OversightToolGate`, ports `RiskPort` / `ApprovalPort` with adapters `EngineRiskPort` / `WorkItemApprovalPort` |
| `governance` | `casehub-langchain4j-governance` | `io.casehub.langchain4j.governance.quarkus` | CDI: registers `GovernanceAgentListener` |
| `governance-spring` | `casehub-langchain4j-governance-spring` | `io.casehub.langchain4j.governance.spring` | Spring auto-config |
| `hybrid-search-core` | `casehub-langchain4j-hybrid-search-core` | `io.casehub.langchain4j.hybridsearch` | Pure Java: `CasehubHybridContentRetriever` → neocortex RAG pipeline |
| `hybrid-search` | `casehub-langchain4j-hybrid-search` | `io.casehub.langchain4j.hybridsearch.quarkus` | CDI: registers `ContentRetriever` |
| `hybrid-search-spring` | `casehub-langchain4j-hybrid-search-spring` | `io.casehub.langchain4j.hybridsearch.spring` | Spring auto-config |
| `crag-core` | `casehub-langchain4j-crag-core` | `io.casehub.langchain4j.crag` | Pure Java: `CragContentRetriever` (grades via neocortex `RelevanceEvaluator`), `CragRetrievalQuality` |
| `crag` | `casehub-langchain4j-crag` | `io.casehub.langchain4j.crag.quarkus` | CDI: `@Alternative @Priority(200)` producer wrapping the resolved `ContentRetriever` (outside tenancy's 100) |
| `crag-spring` | `casehub-langchain4j-crag-spring` | `io.casehub.langchain4j.crag.spring` | Spring: late-ordered `BeanPostProcessor` (after tenancy's early one) |
| `tracking-core` | `casehub-langchain4j-tracking-core` | `io.casehub.langchain4j.tracking` | Pure Java: `TrackingContentRetriever` (records into neocortex `RetrievalTracker`); test-jar `RecordingTracker` |
| `tracking` | `casehub-langchain4j-tracking` | `io.casehub.langchain4j.tracking.quarkus` | CDI: `@Alternative @Priority(300)` producer wrapping the resolved `ContentRetriever` (outside crag's 200) |
| `tracking-spring` | `casehub-langchain4j-tracking-spring` | `io.casehub.langchain4j.tracking.spring` | Spring: `BeanPostProcessor` ordered after crag's; skips the hybrid-search bean |
| `bom` | `casehub-langchain4j-bom` | — | Maven BOM importing all published modules |
| `examples` | `casehub-langchain4j-examples` | `io.casehub.langchain4j.examples` | Working demos. Not published (`maven.deploy.skip=true`). |

All modules use GroupId `io.casehub.langchain4j`.

**Optional CaseHub types.** A class that names a type from an optional dependency (`LedgerAppender`, `CurrentPrincipal`, `TrustScoreSource`, …) must only do so inside a nested holder class that is loaded after a `Class.forName` / `ClassUtils.isPresent` check, and must look the bean up programmatically. An injection point of an absent type breaks startup.

**Listeners observe, they do not veto.** LangChain4j swallows exceptions thrown from `AgentListener` callbacks (`ListenerAbortContractTest` pins this). Do not build enforcement into a listener.

---

## CDI Conventions (Quarkus modules)

### Classpath activation

Every Quarkus module activates by classpath presence. No `quarkus.arc.selected-alternatives` or
`application.properties` required.

### Optional CaseHub types

**Quarkus modules inject; they never look beans up.** ArC removes beans that no injection point
uses, and a programmatic lookup (`Arc.container().instance(...)`) is not an injection point —
`quarkus.arc.unremovable-types` does not help either, because it matches bean *classes*, not the
interface you look up. A class-style `LedgerAppender` or `@RiskClassifier` bean would vanish and
the module would silently degrade. So a Quarkus producer takes `Instance<T>` parameters:

```java
@Produces @DefaultBean @Singleton
public AuditEntryWriter auditEntryWriter(Instance<LedgerAppender> appender, Instance<CurrentPrincipal> principal) {
    if (appender.isResolvable() && principal.isResolvable()) { ... }
    return new LoggingAuditEntryWriter();
}
```

That requires the type on the classpath, so the API jar a Quarkus module injects from is a
required dependency of that module (`casehub-ledger-api`, `casehub-platform-api`,
`casehub-engine-api`). `ClassStyle*DiscoveryTest`s prove class-style beans from a dependency jar
are kept.

**Spring modules** have no unused-bean removal: they may keep API jars optional, check with
`ClassUtils.isPresent`, touch the types only in a nested holder, and resolve beans through
`BeanFactory.getBeanProvider(...).getIfAvailable()`.

### Bean roles

| Role | Annotation | Example |
|---|---|---|
| Replaceable default | `@DefaultBean` | `AuditEntryWriter`, `CasehubAgentListener` from the audit module |
| Replaces a module default | plain bean | `GovernanceAgentListener` replaces the audit agent listener |
| Decorator over the app's bean | `@Alternative @Priority(100) @ApplicationScoped` producer | tenancy wrappers; `@ApplicationScoped` so a class can declare and inject a store without a creation cycle |
| Opt-in implementation | own qualifier (`@HybridSearch`) + `@Named` | hybrid retriever; never an unqualified candidate |

Decorating modules stack by priority: tenancy (100) wraps the application's bean, crag (200) wraps
tenancy, tracking (300) wraps crag — Tracking(CRAG(Tenancy(app))). A decorator never selects an
alternative that outranks its own as its delegate, or two decorators would wrap each other; it warns
about an outranking alternative only when the bean class is outside `io.casehub.langchain4j` (this
library's own outer decorators wrap it by design). In Spring the same order comes from
post-processor order: tenancy runs early (`HIGHEST_PRECEDENCE + 100`), crag late
(`LOWEST_PRECEDENCE - 100`), tracking last (`LOWEST_PRECEDENCE - 50`).

A decorator producer finds the application's bean with `BeanManager.getBeans` — `Instance`
resolution would only return the decorator itself — and applies ArC's rules (alternatives, then
non-`@DefaultBean` beans) before giving up as ambiguous.

### Jandex

Every Quarkus module includes the `jandex-maven-plugin` (`make-index` execution) so ArC discovers
its beans from the jar.

---

## Spring Conventions

### Auto-configuration

Each module provides an `@AutoConfiguration` class, gated with `@ConditionalOnClass` on the
LangChain4j SPI, and registered in
`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`.

Optional CaseHub types follow the same rule as in Quarkus: check with `ClassUtils.isPresent`, touch
them only in a nested holder, and resolve beans through `BeanFactory.getBeanProvider(...).getIfAvailable()`.
Every bean is `@ConditionalOnMissingBean`, so an application bean of the same type wins.

Decorators are applied by a `static` `BeanPostProcessor` (see `TenancyAutoConfiguration`). An
opt-in implementation is registered with `@Bean(defaultCandidate = false)`.

---

## Test Conventions

- **Core modules:** plain JUnit 5 + AssertJ (+ Mockito for CaseHub SPIs). Prefer real LangChain4j
  objects where they are cheap — the agent lineage is tested against real `AgenticServices` agents
  (`CasehubAgentListenerRealAgentTest`), which is what exposed the per-invocation leak.
- **Quarkus modules:** `@QuarkusTest`; optional CaseHub beans are supplied as `@Alternative` test
  beans enabled by a `QuarkusTestProfile`.
- **Spring modules:** `ApplicationContextRunner` (not `@SpringBootTest`); use `FilteredClassLoader`
  to run without the optional CaseHub jars.
- **Characterisation tests:** `ListenerAbortContractTest` pins that LangChain4j swallows listener
  exceptions. If it starts failing after an upgrade, revisit the governance design.
- Test classes are `*Test.java` (surefire). Method names describe behaviour:
  `failedChild_popsFrame_nextChildChainsToParent`.

---

## Dependencies

### Depends on (upstream)

| Repo | Module(s) used | Why |
|---|---|---|
| casehub-platform | `platform-api` (CurrentPrincipal, ActorType) | Tenant identity |
| casehub-ledger | `ledger-api` (LedgerAppender, AuditRecord, TrustScoreSource, OutcomeRecorder) | Audit trail, trust (optional) |
| casehub-neocortex | `rag-api` (CaseRetriever, CaseContextRetriever) | Hybrid search |
| langchain4j | `langchain4j-core`, `langchain4j-agentic` | SPI interfaces |

### Depended on by (downstream)

None — integration tier with no downstream casehubio dependents. Application repos add it as a direct dependency when they want LC4j enrichment.

---

## Build Commands

```bash
mvn install -DskipTests -q                                    # install all modules
TESTCONTAINERS_RYUK_DISABLED=true mvn clean test               # full test suite
TESTCONTAINERS_RYUK_DISABLED=true mvn clean test -pl audit-core # single module
```

---

## Expansion Policy

Adding a new module beyond the four Phase 1 leads requires documented justification (Decision D8). Create a `## Why this module exists` section in the new module's README with one of:

1. **Gap acknowledged** — cite a LangChain4j issue where maintainers acknowledge the gap. Example: hybrid search (#4087).
2. **Different category** — the module provides something LC4j doesn't attempt. Example: CBR memory is case-based reasoning, not a chat persistence competitor.
3. **Customer requested** — a CaseHub deployer needs it and the LC4j community alternative is insufficient for their enterprise requirements.

Without this justification, the module should not be created. This policy exists to prevent the project from being perceived as hostile to or competitive with langchain4j. See `STRATEGY.md` for the full rationale.

---

## Key Conventions

- **Decorator over replacement** — three of four lead modules are decorators. The developer's chosen LC4j implementation is preserved inside the decorator.
- **Optional deps** — core modules use `<optional>true</optional>` for CaseHub deps. Framework modules handle presence/absence at wiring time.
- **No mandatory config** — adding a module to the classpath is the only step. Any configuration is optional tuning, not required setup.
- **Commit refs** — every commit references an issue: `Refs #N` (in progress) or `Closes #N` (completes).
