# Contributor Guide — casehub-langchain4j

## Module Architecture

| Module | ArtifactId | Package | Responsibility |
|---|---|---|---|
| `audit-core` | `casehub-langchain4j-audit-core` | `io.casehub.langchain4j.audit` | Pure Java: `CasehubChatModelListener`, `CasehubAgentListener` → `CaseLedgerEntry` |
| `audit` | `casehub-langchain4j-audit` | `io.casehub.langchain4j.audit.quarkus` | CDI `@Produces` for listener beans. Jandex indexed. |
| `audit-spring` | `casehub-langchain4j-audit-spring` | `io.casehub.langchain4j.audit.spring` | Spring `@Configuration @ConditionalOnClass` auto-config |
| `tenancy-core` | `casehub-langchain4j-tenancy-core` | `io.casehub.langchain4j.tenancy` | Pure Java: `TenantIsolatingChatMemoryStore`, `TenantIsolatingEmbeddingStore`, `TenantIsolatingContentRetriever` |
| `tenancy` | `casehub-langchain4j-tenancy` | `io.casehub.langchain4j.tenancy.quarkus` | CDI bean discovery and wrapping |
| `tenancy-spring` | `casehub-langchain4j-tenancy-spring` | `io.casehub.langchain4j.tenancy.spring` | Spring `BeanPostProcessor` wrapping |
| `governance-core` | `casehub-langchain4j-governance-core` | `io.casehub.langchain4j.governance` | Pure Java: `GovernanceAgentListener` → lineage + oversight + trust |
| `governance` | `casehub-langchain4j-governance` | `io.casehub.langchain4j.governance.quarkus` | CDI: registers `GovernanceAgentListener` |
| `governance-spring` | `casehub-langchain4j-governance-spring` | `io.casehub.langchain4j.governance.spring` | Spring auto-config |
| `hybrid-search-core` | `casehub-langchain4j-hybrid-search-core` | `io.casehub.langchain4j.hybridsearch` | Pure Java: `CasehubHybridContentRetriever` → neocortex RAG pipeline |
| `hybrid-search` | `casehub-langchain4j-hybrid-search` | `io.casehub.langchain4j.hybridsearch.quarkus` | CDI: registers `ContentRetriever` |
| `hybrid-search-spring` | `casehub-langchain4j-hybrid-search-spring` | `io.casehub.langchain4j.hybridsearch.spring` | Spring auto-config |
| `agents-core` | `casehub-langchain4j-agents-core` | `io.casehub.platform.agent.langchain4j` | Pure Java: `ChatModelAgentProvider`, `AgentProviderChatModel`, `ChatModelAgentSession`, `AgentSessionChatModel`, `AgentEventBridge` (moved from platform) |
| `agents` | `casehub-langchain4j-agents` | `io.casehub.platform.agent.langchain4j.quarkus` | CDI: `Langchain4jBeans`, `AgentLangchain4jConfig` (moved from platform) |
| `agents-spring` | `casehub-langchain4j-agents-spring` | `io.casehub.platform.agent.langchain4j.spring` | Spring auto-config (moved from platform) |
| `bom` | `casehub-langchain4j-bom` | — | Maven BOM importing all published modules |
| `examples` | `casehub-langchain4j-examples` | `io.casehub.langchain4j.examples` | Working demos. Not published (`maven.deploy.skip=true`). |

All modules use GroupId `io.casehub.langchain4j` except `agents-*` which retains `io.casehub.platform.agent.langchain4j` for backward compatibility during the migration from platform.

---

## CDI Conventions (Quarkus modules)

### Classpath activation

Every Quarkus module activates by classpath presence. No `quarkus.arc.selected-alternatives` or `application.properties` required.

**Pattern for listener-type modules (audit, governance):**

```java
@ApplicationScoped
public class AuditBeans {

    @Inject @Any Instance<LedgerEntryWriter> ledgerWriterInstance;
    @Inject @Any Instance<EventLog> eventLogInstance;
    @Inject @Any Instance<CurrentPrincipal> principalInstance;

    @Produces @ApplicationScoped
    public CasehubChatModelListener chatModelListener() {
        return new CasehubChatModelListener(
            ledgerWriterInstance.isResolvable() ? ledgerWriterInstance.get() : null,
            eventLogInstance.isResolvable() ? eventLogInstance.get() : null,
            principalInstance.isResolvable() ? principalInstance.get() : null
        );
    }
}
```

`Instance<T>.isResolvable()` probes for optional deps. Constructor accepts null for graceful degradation.

**Pattern for decorator-type modules (tenancy):**

```java
@ApplicationScoped
public class TenancyBeans {

    @Inject @Any Instance<ChatMemoryStore> storeInstance;
    @Inject @Any Instance<CurrentPrincipal> principalInstance;

    @Produces @ApplicationScoped
    public ChatMemoryStore tenantAwareChatMemoryStore() {
        if (!principalInstance.isResolvable()) {
            return storeInstance.get(); // passthrough — single-tenant
        }
        return new TenantIsolatingChatMemoryStore(
            storeInstance.get(),
            principalInstance.get()
        );
    }
}
```

### Priority tiers

| Tier | Annotation | When selected |
|---|---|---|
| Default | `@DefaultBean` | No other bean on classpath |
| Enterprise decorator | `@Alternative @Priority(5)` | CaseHub enrichment active |
| Test override | `@Alternative @Priority(100)` | Test fixture overrides everything |

### Jandex

Every Quarkus module includes the Jandex plugin in its `pom.xml`:

```xml
<plugin>
    <groupId>io.smallrye</groupId>
    <artifactId>jandex-maven-plugin</artifactId>
    <executions>
        <execution>
            <id>make-index</id>
            <goals><goal>jandex</goal></goals>
        </execution>
    </executions>
</plugin>
```

---

## Spring Conventions

### Auto-configuration

Every Spring module provides a `@Configuration` class annotated with `@ConditionalOnClass` to gate on LC4j types being present:

```java
@Configuration
@ConditionalOnClass(ChatModelListener.class)
public class AuditAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(CasehubChatModelListener.class)
    public CasehubChatModelListener casehubChatModelListener(
            @Autowired(required = false) LedgerEntryWriter ledgerWriter,
            @Autowired(required = false) EventLog eventLog,
            @Autowired(required = false) CurrentPrincipal principal) {
        return new CasehubChatModelListener(ledgerWriter, eventLog, principal);
    }
}
```

`@Autowired(required = false)` provides null for graceful degradation — same pattern as CDI `Instance<T>.isResolvable()`.

### META-INF/spring.factories

Each Spring module registers its auto-configuration:

```
org.springframework.boot.autoconfigure.EnableAutoConfiguration=\
  io.casehub.langchain4j.audit.spring.AuditAutoConfiguration
```

Or for Spring Boot 3.x+ (spring.factories deprecated):

```
# META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
io.casehub.langchain4j.audit.spring.AuditAutoConfiguration
```

---

## Test Conventions

### Core modules — unit tests only

```
*-core/src/test/java/.../*Test.java
```

- Mock all CaseHub dependencies (LedgerEntryWriter, CurrentPrincipal, etc.)
- Verify correct method calls on mocks
- Test graceful degradation paths (null deps)
- No framework dependency in tests

### Quarkus modules — @QuarkusTest

```
*/src/test/java/.../*Test.java  (NOT *IT.java)
```

- `@QuarkusTest` with `TESTCONTAINERS_RYUK_DISABLED=true`
- Verify classpath activation: bean is resolvable, listener is registered
- Verify graceful degradation: remove optional dep from test classpath, verify no exception

### Spring modules — @SpringBootTest

```
*-spring/src/test/java/.../*Test.java
```

- `@SpringBootTest` with auto-configuration
- Same verification as Quarkus: classpath activation, graceful degradation

### Test naming

- Test classes: `*Test.java` (never `*IT.java` — surefire, not failsafe)
- Test methods: `should_<expected>_when_<condition>`

---

## Dependencies

### Depends on (upstream)

| Repo | Module(s) used | Why |
|---|---|---|
| casehub-platform | `platform-api` (CurrentPrincipal), `agent-api` (AgentProvider, AgentSession) | Tenancy identity, agent bridge |
| casehub-ledger | `ledger-api` (CaseLedgerEntry, LedgerEntryWriter, ComplianceSupplement) | Audit trail |
| casehub-neocortex | `rag-api` (CaseContextRetriever) | Hybrid search |
| casehub-engine | `engine-api` (OversightGateService, TrustSignalProvider) | Governance (optional) |
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
