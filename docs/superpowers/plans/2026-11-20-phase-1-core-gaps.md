# casehub-langchain4j Phase 1 Core Gaps — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the scaffolded stubs (26 TODOs, 0 tests, empty `agents*`) into working, tested modules for ARC42 chapters C1–C5: audit, tenancy, governance, hybrid-search — and remove the retired agents bridge modules.

**Architecture:** Each group keeps the existing three-tier shape: `-core` (pure Java, all logic and all unit tests), bare name (Quarkus CDI producers only), `-spring` (auto-configuration only). Core classes talk to CaseHub through small ports defined in this repo; adapters to `casehub-ledger-api`, `casehub-engine-api` and `casehub-neocortex-rag-api` are separate classes, so a missing CaseHub dependency degrades to logging/no-op instead of failing.

**Tech Stack:** Java 21, Maven, LangChain4j core `1.19.0` (from `casehub-parent`), `langchain4j-agentic`, Quarkus `3.39.3`, Spring Boot `4.1.1`, JUnit 5, AssertJ, Mockito.

**Spec:** `../engine/docs/specs/casehub-langchain4j/2026-09-24-phase-1-enterprise-concerns.md` (plus `decisions.md`, `STRATEGY.md` in the same folder) and `ARC42STORIES.MD` §9.4 C1–C5.

**Issues:** epic #3; Task 1 → #4, Task 2 → #5, Tasks 3–4 → #6, Task 5 → #7, Task 6 → #8, Task 7 → #9, Task 8 → #10, Task 9 → #11, Task 10 → #12, Task 11 → #13 (the plan text calls these `#E`, `#I1`…`#I10`; `#I3` covers Tasks 3 and 4, so `#I4`…`#I10` map to #7…#13).

**Out of scope:** C6–C30. Phase 2/3 specs are flagged for rewrite in `HANDOFF.md` and Phase 4 has no spec; each needs its own spec and plan. Also out: Testcontainers/Qdrant integration tests, LLM cost estimation (no pricing source exists), blocking human-approval waits (see D5).

## Where the spec is wrong — decisions that replace it

The spec was written before the code it names existed. Verified against the workspace on 2026-11-20:

| # | Spec says | Reality | Decision |
|---|---|---|---|
| D1 | Move agents bridge from `platform/agent-langchain4j*` | Platform **deleted** it on 2026-10-18 (`df70f6cc`, #638, "zero value now that AgentProvider has invokeBlocking()"). Still used by 3 test classes in `casehub-ops/topology-tests`; stale deps in `claudony`, `quarkmind`, `parent` BOM. | **Decided 2026-11-20: the bridge stays removed.** The platform author's call in #638 stands. Task 2 deletes the empty `agents*` modules here; consumers use `AgentProvider` directly. |
| D2 | LC4j `1.14.1`, Quarkus `3.32.2`, Spring Boot `4.1.0` | `casehub-parent` now manages `1.19.0` / `3.39.3` / `4.1.1`; this repo pins `langchain4j-agentic` `1.16.2-beta26`, which does not match core `1.19.0` | Align agentic to the beta released with core `1.19.0`; fix `CLAUDE.md` (Task 1) |
| D3 | `LedgerEntryWriter.write(...)`, `EventLog` fallback, `ComplianceSupplement` on each entry | No `LedgerEntryWriter`. Real API: `io.casehub.ledger.api.spi.LedgerAppender#append(AuditRecord, String tenancyId) : UUID`. `EventLog` is engine-internal. `ComplianceSupplement` is abstract and `AuditRecord` has no supplement slot. | Write `AuditRecord` via `LedgerAppender`; two tiers only (ledger → logging); compliance fields go into `AuditRecord.domainData` |
| D4 | `TrustSignalProvider.score()/recordOutcome()` | `TrustSignalProvider` is an engine routing class with neither method. Real API in `casehub-ledger-api`: `TrustScoreSource#globalScore(String) : OptionalDouble`, `OutcomeRecorder#record(OutcomeRecord, String tenancyId)` | Use those two |
| D5 | `OversightGateService.evaluate()/awaitApproval()` | Real API: `openGate(String agentId, String commitmentId, String outcome, String tenancyId) : GateOutcome` (`Autonomous` \| `GatePending(gateId, reason)`), `fulfill(UUID, String)`. No await. | ~~Fail closed: a pending gate throws `GovernanceGatePendingException`.~~ **Void (found during execution):** LangChain4j catches exceptions thrown by `AgentListener` callbacks and runs the agent anyway (`ListenerAbortContractTest`). A gate cannot be enforced from a listener. Tasks 8–9 delivered lineage + trust only; the gate needs a new design. |
| D6 | `CaseContextRetriever.retrieve(RetrievalRequest) : List<RetrievedContext>` | Real: `retrieve(String queryText, List<CorpusRef> corpora, int maxResults) : List<RetrievedChunk>`; `CorpusRef(tenantId, corpusName)` requires a tenant; nothing produces `CaseContextRetriever` as a bean (only `CaseRetriever`) | Config supplies corpus names, tenant comes from `CurrentPrincipal`; wiring builds `new CaseContextRetriever(caseRetriever)` |
| D7 | Audit and governance each write `AGENT_INVOCATION_*` entries | Both modules on the classpath would double-write lineage | Lineage lives in audit's `CasehubAgentListener`; `GovernanceAgentListener` **extends** it and adds gate + trust. `GovernanceEntryWriter` and `LoggingGovernanceEntryWriter` are deleted. |
| D8 | Agent listeners activate with "zero code change" | LC4j agentic listeners are attached per agent (`.listener(...)` on the builder; `@AgentListenerSupplier` in quarkus-langchain4j) — no global discovery | Modules produce the listener bean; attaching it is one documented line. `ChatModelListener` stays zero-code. |
| D9 | Class names `TenantChatMemoryStore`, `CasehubGovernanceListener`, `*BeanProducer` | Stubs and ARC42 use `TenantIsolatingChatMemoryStore`, `GovernanceAgentListener`, `*Beans` | Keep the existing stub names |

## Global Constraints

- GroupId `io.casehub.langchain4j`, artifactId `casehub-langchain4j-<module>`, version `0.4-SNAPSHOT`; versions inherit from `casehub-parent` — no local overrides except `langchain4j-agentic`.
- `-core` modules have no CDI or Spring imports. Quarkus modules contain producers only; `-spring` modules contain auto-configuration only.
- Decorators over replacements; classpath activation; a missing CaseHub dependency logs and no-ops, never throws at startup.
- A listener must never break the LLM/agent call it observes: every writer call is wrapped, failures are logged at WARN. The only listener exception allowed to propagate is `GovernanceGatePendingException`.
- Never record prompt or completion text, and never a stack trace, in an audit entry (PII) — error class and message only.
- Every commit references a GitHub issue (`Refs #N` / `Closes #N`) in `casehubio/casehub-langchain4j`.
- Per `CLAUDE.md`: call Quill `change_session` before editing sources; prefer `mcp__intellij-index__*` for navigation.
- Run tests with `mvn -q test -pl <module> -am`; full suite `mvn clean test`.

## Review Focus

1. **Tenant ID containing `::`** — tenant `a::b` + memory `c` must not collide with tenant `a` + memory `b::c`; expect rejection. Test in Task 6.
2. **Principal present but `tenancyId()` null/blank** — an isolation feature must fail closed, not pass through; expect `TenantContextMissingException`. Test in Task 6 (and `IllegalStateException` in Task 10).
3. **Caller-supplied `casehub_tenant_id` metadata** — a segment arriving already tagged with another tenant's ID must be overwritten on add and filtered on retrieve. Test in Task 6.
4. **Ledger down or throwing during an LLM call** — the user's chat call must still return; expect WARN + fallback. Test in Task 3.
5. **Nested and failing sub-agents** — a failed sub-agent must not leave a stale frame that mis-parents the next entry; expect the parent's next child to chain to the parent. Test in Task 4. (Parallel sub-agents sharing one `memoryId` are a known gap of the stack model — state it in the consumer guide.)

---

### Task 1: Baseline — versions, test infrastructure, tracking

**Files:**
- Modify: `pom.xml` (properties, `dependencyManagement`)
- Modify: `CLAUDE.md`, `README.md`, `HANDOFF.md` (versions, spec links)
- Create: `audit-core/src/test/java/io/casehub/langchain4j/audit/BuildSmokeTest.java` (deleted again in Task 3)

**Interfaces:**
- Produces: `mockito-core` and `mockito-junit-jupiter` available as managed test deps to every module; issue numbers `#E` (epic), `#I1`…`#I10` (one per task below) used in all later commits.

- [ ] **Step 1:** Create the epic "Phase 1 core: C1–C5" and ten child issues, one per task in this plan, with `gh issue create -R casehubio/casehub-langchain4j`. Record the numbers at the top of this file.
- [ ] **Step 2:** Write `BuildSmokeTest` with one test asserting `new LoggingAuditEntryWriter()` is non-null. Run `mvn -q test -pl audit-core -am`; expected: PASS, 1 test (proves surefire + JUnit 5 run at all — there are currently zero tests).
- [ ] **Step 3:** In `pom.xml`, set `version.dev.langchain4j.agentic` to the `langchain4j-agentic` beta published alongside core `1.19.0` (look it up on Maven Central; same `1.19.0-betaNN` scheme). Add `mockito-core` / `mockito-junit-jupiter` to `dependencyManagement` only if `casehub-parent` does not already manage them.
- [ ] **Step 4:** Run `mvn -q -DskipTests install`. Expected: BUILD SUCCESS. If the agentic bump breaks `GovernanceAgentListener`, fix only the compile errors here.
- [ ] **Step 5:** Update `CLAUDE.md` versions to `1.19.0` / `3.39.3` / `4.1.1`. Replace every `wksp/specs/casehub-langchain4j/...` link in `README.md`, `CLAUDE.md` and the stale `~/claude/public/...` path in `HANDOFF.md` with `https://github.com/casehubio/engine/tree/main/docs/specs/casehub-langchain4j/...`.
- [ ] **Step 6:** Commit: `build: align LC4j agentic version, add test infra, fix spec links (Closes #I1)`.

---

### Task 2: Remove the agents bridge modules (C1, decision D1)

**Files:**
- Delete: `agents-core/`, `agents/`, `agents-spring/`, `agents-deployment/` (each holds only a pom and `.gitkeep` files)
- Modify: `pom.xml` (three `<module>` lines; the `casehub-langchain4j-agents-core` entry in `dependencyManagement`; the `casehub-platform-agent-api` entry stays — governance-core uses it)
- Modify: `bom/pom.xml` (agents entries)
- Modify: `README.md`, `CLAUDE.md` (module tables), `HANDOFF.md` ("Must do" item 1), `docs/VISION.md`, `docs/guides/*.md`, `ARC42STORIES.MD` (C1 title, key files, issue lines; §5 building blocks; §12 "Platform module migration" risk)

**Interfaces:**
- Produces: a reactor with 12 published modules + `bom` + `examples`; no artifact named `casehub-langchain4j-agents*`.

- [ ] **Step 1:** Run `grep -rn "agents" --include=pom.xml .` and `grep -rniE "agents-(core|spring)|agents bridge|AgentProviderChatModel|ChatModelAgentProvider" --include='*.md' --include='*.MD' .` and keep the output as the checklist of references to remove.
- [ ] **Step 2:** Delete the four directories and remove every pom reference from Step 1.
- [ ] **Step 3:** Run `mvn -q -DskipTests install`. Expected: BUILD SUCCESS with no `agents` module in the reactor summary.
- [ ] **Step 4:** Update the docs from Step 1: state that the ChatModel ↔ AgentProvider bridge was retired in platform #638 and that consumers call `AgentProvider` directly; rename C1 to "Scaffold". Re-run the second grep. Expected: no matches outside this plan file.
- [ ] **Step 5:** Commit: `chore: remove agents bridge modules, retired in platform#638 (Closes #I2)`.

Stale references in other repositories (`parent` BOM, `casehub-ops/topology-tests`, `claudony/app`, `quarkmind`) are cross-repo work — Task 11 opens the issues; they are not fixed from this repo.

---

### Task 3: audit-core — event model, writers, `CasehubChatModelListener` (C2)

**Files:**
- Create: `audit-core/src/main/java/io/casehub/langchain4j/audit/AuditEvent.java`, `LedgerAuditEntryWriter.java`
- Modify: `audit-core/.../AuditEntryWriter.java`, `LoggingAuditEntryWriter.java`, `CasehubChatModelListener.java`
- Test: `audit-core/src/test/java/io/casehub/langchain4j/audit/{CasehubChatModelListenerTest,LedgerAuditEntryWriterTest}.java`; delete `BuildSmokeTest.java`

**Interfaces:**
- Produces:
  - `record AuditEvent(String kind, String actorId, UUID subjectId, UUID causedByEntryId, Map<String,Object> data)` — `kind`, `actorId`, `subjectId` non-null; `data` defensively copied.
  - `interface AuditEntryWriter { UUID write(AuditEvent event); }` — returns the entry ID, never null. (Replaces `writeCompletion`/`writeError`.)
  - `LoggingAuditEntryWriter` — logs at INFO, returns `UUID.randomUUID()`.
  - `LedgerAuditEntryWriter(LedgerAppender appender, Supplier<String> tenancyId, AuditEntryWriter fallback)`.
  - `CasehubChatModelListener(AuditEntryWriter writer)`.
  - Kind constants on `AuditEvent`: `AI_CHAT_COMPLETION`, `AI_CHAT_ERROR`, `AGENT_INVOCATION_STARTED`, `AGENT_INVOCATION_COMPLETED`, `AGENT_INVOCATION_FAILED`, `AGENT_TOOL_EXECUTED`.

- [ ] **Step 1: Write failing tests** (`CasehubChatModelListenerTest`, recording fake `AuditEntryWriter`):
  - `onResponse_writesCompletionEvent` — one event, `kind == "AI_CHAT_COMPLETION"`, `actorId == "llm:gpt-test"`, `data` has exactly keys `model, inputTokens, outputTokens, totalTokens, latencyMs, finishReason` with `inputTokens == 10`, `outputTokens == 5`, `totalTokens == 15`, `latencyMs >= 0`.
  - `onResponse_nullTokenUsage_omitsTokenKeys` — no NPE; the three token keys absent.
  - `onResponse_usesSubjectIdFromOnRequest` — `subjectId` equals the UUID stored in `ctx.attributes()` under `casehub.audit.subjectId` by `onRequest`.
  - `onError_writesErrorClassAndMessageOnly` — `kind == "AI_CHAT_ERROR"`, `data` keys exactly `model, errorClass, errorMessage`; no value contains `"\tat "`.
  - `writerThrows_listenerDoesNotPropagate` — writer throws `RuntimeException`; `onResponse` returns normally. *(Review Focus 4)*
  - `neverRecordsMessageText` — request with user message `"SECRET"`; no event value contains `"SECRET"`.
- [ ] **Step 2:** Run `mvn -q test -pl audit-core -am -Dtest=CasehubChatModelListenerTest`. Expected: FAIL (compilation — `AuditEvent` missing).
- [ ] **Step 3:** Implement `AuditEvent`, the new `AuditEntryWriter`, `LoggingAuditEntryWriter`, and `CasehubChatModelListener`. `onRequest` stores `System.nanoTime()` and a random `UUID` subject in `ctx.attributes()`.
- [ ] **Step 4:** Run the same command. Expected: PASS, 6 tests.
- [ ] **Step 5: Write failing tests** (`LedgerAuditEntryWriterTest`, Mockito `LedgerAppender`):
  - `write_appendsAuditRecordWithTenancy` — `append` called once with tenancy `"acme"`; captured `AuditRecord` has the event's `subjectId`, `actorId`, `causedByEntryId`, `entryType == LedgerEntryType.EVENT`, non-null `occurredAt`, and `domainData` equal to the event data plus `"casehub.lc4j.kind" -> kind`; returns the UUID `append` returned.
  - `write_blankTenancy_usesFallback` — supplier returns `null` then `" "`; `append` never called; fallback called both times.
  - `write_appenderThrows_usesFallback` — fallback's UUID returned. *(Review Focus 4)*
- [ ] **Step 6:** Run with `-Dtest=LedgerAuditEntryWriterTest`. Expected: FAIL. Implement `LedgerAuditEntryWriter`. Run again. Expected: PASS, 3 tests.
- [ ] **Step 7:** Commit: `feat(audit): ledger-backed chat model audit (Refs #I3)`.

---

### Task 4: audit-core — `CasehubAgentListener` with causal lineage (C2)

**Files:**
- Create: `audit-core/src/main/java/io/casehub/langchain4j/audit/CasehubAgentListener.java`
- Test: `audit-core/src/test/java/io/casehub/langchain4j/audit/CasehubAgentListenerTest.java`

**Interfaces:**
- Consumes: `AuditEvent`, `AuditEntryWriter` (Task 3).
- Produces: `public class CasehubAgentListener implements AgentListener` with constructor `(AuditEntryWriter writer)`, `inheritedBySubagents() == true`, and two protected hooks for Task 8:
  - `protected Map<String,Object> startData(AgentRequest request)` — default `Map.of()`; merged into the STARTED event.
  - `protected UUID subjectIdFor(Object memoryId)` — `UUID.nameUUIDFromBytes(("lc4j-agent:" + memoryId).getBytes(UTF_8))`.

Lineage state is a listener-internal `ConcurrentHashMap<Object, Deque<Frame>>` keyed by `memoryId`, `Frame(UUID entryId, long startNanos)`. It is **not** written into `AgenticScope` state (that state is user-visible and may be persisted). `beforeAgenticScopeDestroyed` removes the key.

- [ ] **Step 1: Write failing tests** (Mockito mocks for `AgentRequest`/`AgentResponse`/`AgentInvocationError`/`AfterAgentToolExecution`/`AgenticScope`; recording writer returning sequential UUIDs):
  - `rootInvocation_startedHasNoCause_completedCausedByStarted` — events `[AGENT_INVOCATION_STARTED(cause=null), AGENT_INVOCATION_COMPLETED(cause=id1)]`; `actorId == "agent:supervisor"`; COMPLETED `data` has `agentName`, `durationMs >= 0`.
  - `nestedInvocation_childStartedCausedByParentStarted` — before(parent), before(child) → child STARTED `cause == parent id`.
  - `toolExecution_causedByCurrentInvocation` — `AGENT_TOOL_EXECUTED`, `data.toolName == "search"`, cause = current frame.
  - `failedChild_popsFrame_nextChildChainsToParent` — before(P), before(C1), error(C1), before(C2) → C2 STARTED `cause == P id`; FAILED `data` keys exactly `agentName, errorClass, errorMessage`. *(Review Focus 5)*
  - `sameMemoryId_sameSubjectId` / `differentMemoryId_differentSubjectId`.
  - `scopeDestroyed_clearsState` — after `beforeAgenticScopeDestroyed`, a new root STARTED has `cause == null`.
  - `writerThrows_doesNotPropagate`.
  - `inheritedBySubagents_isTrue`.
- [ ] **Step 2:** Run `mvn -q test -pl audit-core -am -Dtest=CasehubAgentListenerTest`. Expected: FAIL (class missing).
- [ ] **Step 3:** Implement `CasehubAgentListener`.
- [ ] **Step 4:** Run again. Expected: PASS, 10 tests.
- [ ] **Step 5:** Commit: `feat(audit): agent invocation lineage listener (Closes #I3)`.

---

### Task 5: audit — Quarkus and Spring wiring (C2)

**Files:**
- Modify: `audit/src/main/java/io/casehub/langchain4j/audit/quarkus/AuditBeans.java`, `audit/pom.xml`
- Modify: `audit-spring/src/main/java/io/casehub/langchain4j/audit/spring/AuditAutoConfiguration.java`, `audit-spring/pom.xml`
- Create: `audit-spring/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` (no `-spring` module has one today, so no auto-configuration is ever loaded)
- Test: `audit/src/test/java/io/casehub/langchain4j/audit/quarkus/{AuditBeansLoggingTest,AuditBeansLedgerTest}.java`, `audit-spring/src/test/java/io/casehub/langchain4j/audit/spring/AuditAutoConfigurationTest.java`

**Interfaces:**
- Consumes: Tasks 3–4.
- Produces: beans `AuditEntryWriter`, `CasehubChatModelListener`, `CasehubAgentListener`. In Quarkus all three are `@DefaultBean @Singleton`; in Spring all three are `@ConditionalOnMissingBean`. Task 9 relies on `CasehubAgentListener` being overridable.

Writer selection, both frameworks: `LedgerAppender` bean **and** `CurrentPrincipal` bean present → `LedgerAuditEntryWriter(appender, principal::tenancyId, new LoggingAuditEntryWriter())`; otherwise `LoggingAuditEntryWriter`. In Quarkus resolve both with `Instance<T>.isResolvable()`; in Spring use `ObjectProvider<T>.getIfAvailable()`. Types from optional jars must only appear inside a nested static holder class so the outer class loads without them.

- [ ] **Step 1:** Write `AuditAutoConfigurationTest` (`ApplicationContextRunner`): `noLedger_usesLoggingWriter`; `ledgerAndPrincipalBeans_usesLedgerWriter`; `userWriterBean_wins`; `userAgentListenerSubclass_replacesDefault` (context has exactly one `CasehubAgentListener`). Run `mvn -q test -pl audit-spring -am`. Expected: FAIL.
- [ ] **Step 2:** Implement `AuditAutoConfiguration` as `@AutoConfiguration @ConditionalOnClass(ChatModelListener.class)`; the agent-listener bean in a nested `@Configuration @ConditionalOnClass(AgentListener.class)`. Add the `.imports` file. Run again. Expected: PASS, 4 tests.
- [ ] **Step 3:** Write the two `@QuarkusTest` classes (add `quarkus-junit5` test dep): `AuditBeansLoggingTest` — injected `AuditEntryWriter` is a `LoggingAuditEntryWriter`, both listeners injectable; `AuditBeansLedgerTest` — test-scoped mock `LedgerAppender` and `CurrentPrincipal` beans (tenancy `"acme"`), fire `listener.onRequest/onResponse`, verify `append(any(), eq("acme"))`. Run `mvn -q test -pl audit -am`. Expected: FAIL.
- [ ] **Step 4:** Implement `AuditBeans`. Run again. Expected: PASS.
- [ ] **Step 5:** Commit: `feat(audit): Quarkus and Spring wiring (Closes #I4)`.

---

### Task 6: tenancy-core — three decorators (C3)

**Files:**
- Create: `tenancy-core/src/main/java/io/casehub/langchain4j/tenancy/{TenantContext,TenantContextMissingException,TenantIsolatingEmbeddingStore}.java`
- Modify: `tenancy-core/.../TenantIsolatingChatMemoryStore.java`, `TenantIsolatingContentRetriever.java`
- Test: `tenancy-core/src/test/java/io/casehub/langchain4j/tenancy/{TenantContextTest,TenantIsolatingChatMemoryStoreTest,TenantIsolatingEmbeddingStoreTest,TenantIsolatingContentRetrieverTest}.java`

**Interfaces:**
- Produces:
  - `final class TenantContext` — `TenantContext(CurrentPrincipal principalOrNull)`; `Optional<String> tenantId()`; `static final String METADATA_KEY = "casehub_tenant_id"`; `static final String SEPARATOR = "::"`.
  - `TenantContextMissingException extends IllegalStateException`.
  - `TenantIsolatingChatMemoryStore(ChatMemoryStore delegate, CurrentPrincipal principalOrNull)`
  - `TenantIsolatingEmbeddingStore(EmbeddingStore<TextSegment> delegate, CurrentPrincipal principalOrNull) implements EmbeddingStore<TextSegment>`
  - `TenantIsolatingContentRetriever(ContentRetriever delegate, CurrentPrincipal principalOrNull)`

`TenantContext.tenantId()` rules — the one place the policy lives:

| Principal | `tenancyId()` | Result |
|---|---|---|
| `null` (no CaseHub identity on classpath) | — | `Optional.empty()` → decorators pass through unchanged |
| present | null or blank | throw `TenantContextMissingException` |
| present | contains `::` | throw `TenantContextMissingException` |
| present | anything else | `Optional.of(id)` |

`isCrossTenantAdmin()` grants no bypass in Phase 1.

- [ ] **Step 1: Write failing tests.**
  - `TenantContextTest`: one test per table row. *(Review Focus 1, 2)*
  - `TenantIsolatingChatMemoryStoreTest` over `InMemoryChatMemoryStore`: `sameMemoryId_differentTenants_noLeakage` (tenant `a` writes; tenant `b` reads empty; `a` still reads its message); `delete_onlyAffectsOwnTenant`; `delegateSeesScopedId` (`"a::m1"`); `nullPrincipal_passesThroughUnscoped`; `blankTenant_throws`.
  - `TenantIsolatingEmbeddingStoreTest` over `InMemoryEmbeddingStore<TextSegment>`: `add_tagsSegmentWithTenant`; `add_overwritesCallerSuppliedTenantKey` *(Review Focus 3)*; `search_returnsOnlyOwnTenant` (same embedding stored by `a` and `b`; `a` gets 1 match); `search_andsExistingFilter` (caller filter `category = x` still applies); `addAllWithSegments_tagsEach`; `addWithoutSegment_throwsUnsupportedOperation` (`add(Embedding)`, `add(String, Embedding)`, `addAll(List<Embedding>)` cannot carry a tenant tag — message names the method to use instead); `removeAllByFilter_scopedToTenant`; `removeAll_removesOnlyOwnTenant`; `nullPrincipal_passesThrough`.
  - `TenantIsolatingContentRetrieverTest`: `filtersOutOtherTenantsContent`; `dropsContentWithoutTenantKey` (fail closed); `nullPrincipal_passesThrough`; `blankTenant_throws`.
- [ ] **Step 2:** Run `mvn -q test -pl tenancy-core -am`. Expected: FAIL (compilation).
- [ ] **Step 3:** Implement `TenantContext` + exception, then the three decorators. In the embedding store, `search` rebuilds the `EmbeddingSearchRequest` with `Filter.and(existing, metadataKey(METADATA_KEY).isEqualTo(tenant))`; `removeAll()` becomes `removeAll(tenantFilter)`; `remove(String)` / `removeAll(Collection<String>)` delegate unchanged (IDs carry no tenant — document this in the class Javadoc).
- [ ] **Step 4:** Run again. Expected: PASS, all four classes.
- [ ] **Step 5:** Commit: `feat(tenancy): tenant-isolating store and retriever decorators (Closes #I5)`.

---

### Task 7: tenancy — Quarkus and Spring wiring (C3)

**Files:**
- Modify: `tenancy/src/main/java/io/casehub/langchain4j/tenancy/quarkus/TenancyBeans.java`, `tenancy/pom.xml`
- Modify: `tenancy-spring/src/main/java/io/casehub/langchain4j/tenancy/spring/TenancyAutoConfiguration.java`, `tenancy-spring/pom.xml`
- Create: `tenancy-spring/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Test: `tenancy/src/test/java/io/casehub/langchain4j/tenancy/quarkus/TenancyBeansTest.java`, `tenancy-spring/src/test/java/io/casehub/langchain4j/tenancy/spring/TenancyAutoConfigurationTest.java`

**Interfaces:**
- Consumes: Task 6 constructors.
- Produces: an unqualified injection of `ChatMemoryStore`, `EmbeddingStore<TextSegment>` or `ContentRetriever` resolves to the tenant-isolating wrapper around the application's own bean.

- [ ] **Step 1:** Write `TenancyAutoConfigurationTest` (`ApplicationContextRunner`, user config with an `InMemoryChatMemoryStore` bean and a switchable `CurrentPrincipal`): `wrapsUserChatMemoryStore` (bean is a `TenantIsolatingChatMemoryStore`); `twoTenants_sameMemoryId_noLeakage`; `noStoreBean_contextStartsWithoutWrapper`; `doesNotDoubleWrap`; equivalents for `EmbeddingStore` and `ContentRetriever`. Run `mvn -q test -pl tenancy-spring -am`. Expected: FAIL.
- [ ] **Step 2:** Implement `TenancyAutoConfiguration` as an `@AutoConfiguration @ConditionalOnClass(CurrentPrincipal.class)` registering one `static` `BeanPostProcessor` bean that wraps, in `postProcessAfterInitialization`, any bean that is a `ChatMemoryStore` / `EmbeddingStore` / `ContentRetriever` and not already a `TenantIsolating*`; it resolves `CurrentPrincipal` lazily through `ObjectProvider`. (Not the spec's `@Primary` bean: that injects its own type and cycles.) Run again. Expected: PASS.
- [ ] **Step 3:** Write `TenancyBeansTest` (`@QuarkusTest`; test app defines `@Produces @Singleton @Named("raw") ChatMemoryStore` returning an `InMemoryChatMemoryStore`, and a mutable test `CurrentPrincipal`): `unqualifiedInjection_isTenantWrapper`; `twoTenants_sameMemoryId_noLeakage`; `namedRawStore_stillInjectable`. Run `mvn -q test -pl tenancy -am`. Expected: FAIL.
- [ ] **Step 4:** Implement `TenancyBeans`: three `@Produces @Alternative @Priority(100) @Singleton` methods, each taking `@Any Instance<T>`, picking the first handle whose `getBean().getBeanClass()` is not `TenancyBeans`, and wrapping it. `@Singleton` producers are lazy, so an application with no store never invokes them. Run again. Expected: PASS. **If ArC reports an ambiguous or cyclic resolution, stop and report** — the fallback is a build-time step in a Quarkus extension deployment module, which is a design change, not a tweak.
- [ ] **Step 5:** Commit: `feat(tenancy): Quarkus and Spring wiring (Closes #I6)`.

---

### Task 8: governance-core — gate, trust, lineage (C4)

**Files:**
- Create: `governance-core/src/main/java/io/casehub/langchain4j/governance/{OversightCheck,PendingGate,TrustPort,GovernanceGatePendingException,EngineOversightCheck,LedgerTrustPort}.java`
- Modify: `governance-core/.../GovernanceAgentListener.java`, `governance-core/pom.xml` (add `casehub-langchain4j-audit-core`; `casehub-ledger-api` and `casehub-engine-api` stay `optional`)
- Delete: `governance-core/.../GovernanceEntryWriter.java`, `governance/.../quarkus/LoggingGovernanceEntryWriter.java`
- Test: `governance-core/src/test/java/io/casehub/langchain4j/governance/{GovernanceAgentListenerTest,EngineOversightCheckTest,LedgerTrustPortTest,ListenerAbortContractTest}.java`

**Interfaces:**
- Consumes: `CasehubAgentListener` with hooks `startData`, `subjectIdFor` (Task 4); `AuditEntryWriter` (Task 3).
- Produces:
  - `interface OversightCheck { Optional<PendingGate> check(String agentName, String commitmentId, String tenancyId); }`
  - `record PendingGate(UUID gateId, String reason)`
  - `interface TrustPort { OptionalDouble score(String agentName); void recordOutcome(String agentName, UUID subjectId, boolean success, String tenancyId); }`
  - `GovernanceGatePendingException extends RuntimeException` with `agentName()`, `gateId()`, `reason()`.
  - `GovernanceAgentListener extends CasehubAgentListener`, constructor `(AuditEntryWriter writer, OversightCheck oversightOrNull, TrustPort trustOrNull, Supplier<String> tenancyId)`.
  - `EngineOversightCheck(OversightGateService service)`, `LedgerTrustPort(TrustScoreSource source, OutcomeRecorder recorder, double confidence)`.

- [ ] **Step 1: Pin two unknowns before writing code.**
  (a) `ListenerAbortContractTest`: build a real one-agent `AgenticServices` agent over a stub `ChatModel`, attach a listener whose `beforeAgentInvocation` throws; assert the invocation throws and the model was never called. **If LC4j swallows listener exceptions, stop and report** — the gate cannot be enforced from a listener and D5 needs a new design.
  (b) Read `SupervisorGateBehavior` and one production caller of `OversightGateService.openGate` in `../engine` to fix what `commitmentId` and `outcome` mean; write the answer into the `EngineOversightCheck` Javadoc.
- [ ] **Step 2: Write failing tests** (`GovernanceAgentListenerTest`):
  - `noPorts_behavesLikeAuditListener` — same events as Task 4's root test.
  - `gateAutonomous_invocationProceeds` — `check` returns empty; STARTED written.
  - `gatePending_throwsAndRecordsFailure` — `check` returns `PendingGate(g, "high risk")`; `GovernanceGatePendingException` with that `gateId`; events `[STARTED, FAILED]`, FAILED `data.errorClass == "GovernanceGatePendingException"`; lineage frame popped.
  - `oversightThrows_failsClosed` — port throws `RuntimeException` → `GovernanceGatePendingException`, reason `"oversight unavailable"`.
  - `trustScorePresent_recordedInStartedData` — `score` returns `0.82` → STARTED `data.trustScore == 0.82`.
  - `trustScoreAbsent_keyOmitted`.
  - `success_recordsPositiveOutcome` — `recordOutcome(name, subjectId, true, "acme")` once.
  - `error_recordsNegativeOutcome` — `success == false`.
  - `trustPortThrows_doesNotPropagate`.
  - `blankTenancy_skipsOversightAndTrust_keepsLineage`.
- [ ] **Step 3:** Run `mvn -q test -pl governance-core -am -Dtest=GovernanceAgentListenerTest`. Expected: FAIL. Implement ports, exception and listener. Run again. Expected: PASS, 10 tests.
- [ ] **Step 4: Write failing adapter tests.**
  - `EngineOversightCheckTest`: `autonomous_mapsToEmpty`; `gatePending_mapsToPendingGate` (same `gateId`, `reason`).
  - `LedgerTrustPortTest`: `score_delegatesToGlobalScore` with `actorId == "agent:" + name` (same actor ID the audit entries use); `recordOutcome_success_recordsSound` — captured `OutcomeRecord` has `verdict == SOUND`, `confidence == 0.7`, `capabilityTag == CapabilityTag.GLOBAL`, the given `subjectId`, non-null `occurredAt`; recorded with the given tenancy; `recordOutcome_failure_recordsFlagged`.
- [ ] **Step 5:** Run `mvn -q test -pl governance-core -am`. Expected: FAIL → implement both adapters → PASS.
- [ ] **Step 6:** Commit: `feat(governance): oversight gate, trust outcomes, shared lineage (Closes #I7)`.

---

### Task 9: governance — Quarkus and Spring wiring (C4)

**Files:**
- Modify: `governance/src/main/java/io/casehub/langchain4j/governance/quarkus/GovernanceBeans.java`, `governance/pom.xml`
- Modify: `governance-spring/src/main/java/io/casehub/langchain4j/governance/spring/GovernanceAutoConfiguration.java`, `governance-spring/pom.xml`
- Create: `governance-spring/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Test: `governance/src/test/java/io/casehub/langchain4j/governance/quarkus/GovernanceBeansTest.java`, `governance-spring/src/test/java/io/casehub/langchain4j/governance/spring/GovernanceAutoConfigurationTest.java`

**Interfaces:**
- Consumes: Task 8 constructors; `AuditEntryWriter` bean and overridable `CasehubAgentListener` bean from Task 5 (governance modules depend on the matching audit module).
- Produces: a single `CasehubAgentListener` bean of runtime type `GovernanceAgentListener`. Property `casehub.langchain4j.governance.outcome-confidence`, default `0.7`, valid range `(0.0, 1.0]`.

Port selection: `OversightGateService` bean present → `EngineOversightCheck`, else `null`. `TrustScoreSource` **and** `OutcomeRecorder` beans present → `LedgerTrustPort`, else `null`. Tenancy supplier: `CurrentPrincipal::tenancyId` if a principal bean exists, else `() -> null`. Optional-jar types only inside nested holder classes.

- [ ] **Step 1:** Write `GovernanceAutoConfigurationTest`: `replacesAuditAgentListener` (exactly one `CasehubAgentListener`, instance of `GovernanceAgentListener`); `noEngineNoLedger_startsWithNullPorts` (an invocation writes lineage, throws nothing); `gateServiceBean_isUsed` (mock `OversightGateService` returning `GatePending` → `beforeAgentInvocation` throws); `invalidConfidence_failsStartup` (`0.0` and `1.5`). Run `mvn -q test -pl governance-spring -am`. Expected: FAIL.
- [ ] **Step 2:** Implement `GovernanceAutoConfiguration` (`@AutoConfiguration(before = AuditAutoConfiguration.class) @ConditionalOnClass(AgentListener.class)`) and its `.imports` file. Run again. Expected: PASS.
- [ ] **Step 3:** Write `GovernanceBeansTest` (`@QuarkusTest`) with the same four cases. Run `mvn -q test -pl governance -am`. Expected: FAIL. Implement `GovernanceBeans` producing `@Singleton GovernanceAgentListener` (a non-default bean, so it beats audit's `@DefaultBean`). Run again. Expected: PASS.
- [ ] **Step 4:** Commit: `feat(governance): Quarkus and Spring wiring (Closes #I8)`.

---

### Task 10: hybrid-search — retriever and wiring (C5)

**Files:**
- Create: `hybrid-search-core/src/main/java/io/casehub/langchain4j/hybridsearch/HybridSearchConfig.java`
- Modify: `hybrid-search-core/.../CasehubHybridContentRetriever.java`
- Modify: `hybrid-search/.../quarkus/HybridSearchBeans.java`, `hybrid-search-spring/.../spring/HybridSearchAutoConfiguration.java`, both poms
- Create: `hybrid-search-spring/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Test: `hybrid-search-core/src/test/java/io/casehub/langchain4j/hybridsearch/CasehubHybridContentRetrieverTest.java`, `hybrid-search/src/test/java/.../quarkus/HybridSearchBeansTest.java`, `hybrid-search-spring/src/test/java/.../spring/HybridSearchAutoConfigurationTest.java`

**Interfaces:**
- Produces:
  - `record HybridSearchConfig(List<String> corpusNames, int maxResults)` — `corpusNames` non-empty, `maxResults >= 1`, else `IllegalArgumentException`.
  - `CasehubHybridContentRetriever(CaseContextRetriever delegate, Supplier<String> tenantId, HybridSearchConfig config)`.
  - Bean named `casehubHybridRetriever` of type `ContentRetriever`. It is **not** `@Alternative`, `@DefaultBean` or `@Primary` — hybrid search replaces a retriever, so the developer opts in by name.
  - Properties `casehub.langchain4j.hybrid-search.corpora` (comma-separated, required), `casehub.langchain4j.hybrid-search.max-results` (default `10`).

Mapping, per `RetrievedChunk`: `Content.from(TextSegment.from(content, metadata), Map.of(ContentMetadata.SCORE, relevanceScore))`, where metadata is the chunk's metadata plus `source_document_id` and `relevance_grade` (`grade().name()`). The spec's `rerankingEnabled` / `rerankingTopN` are dropped: `CaseContextRetriever.retrieve` has no such parameters; reranking is configured inside neocortex.

- [ ] **Step 1: Write failing tests** (`CasehubHybridContentRetrieverTest`, `CaseContextRetriever` built over a fake `CaseRetriever`):
  - `mapsChunksToContent_preservingOrderScoreAndMetadata` — two chunks, scores `0.9`, `0.4`; contents in that order; `metadata(SCORE) == 0.9`; segment metadata has the chunk's own keys plus `source_document_id` and `relevance_grade`.
  - `queriesOneCorpusRefPerConfiguredName_withCurrentTenant` — config `["kb","faq"]`, tenant `acme` → fake sees `CorpusRef("acme","kb")` and `CorpusRef("acme","faq")`.
  - `passesMaxResults` — config `3` reaches the fake.
  - `blankQuery_returnsEmpty_withoutCallingDelegate`.
  - `blankContentChunk_isSkipped` (`TextSegment.from` rejects blank text).
  - `missingTenant_throwsIllegalState` — supplier returns `null`; message names `CurrentPrincipal`. *(Review Focus 2)*
  - `config_rejectsEmptyCorpora` / `config_rejectsZeroMaxResults`.
- [ ] **Step 2:** Run `mvn -q test -pl hybrid-search-core -am`. Expected: FAIL. Implement config + retriever. Run again. Expected: PASS, 8 tests.
- [ ] **Step 3:** Write `HybridSearchAutoConfigurationTest`: `caseRetrieverBean_registersNamedRetriever`; `noCaseRetriever_noBean_contextStarts`; `userContentRetriever_coexists` (two `ContentRetriever` beans, user's stays primary); `missingCorporaProperty_failsWithPropertyName`. Mirror the first three in `HybridSearchBeansTest` (`@QuarkusTest`, fake `CaseRetriever` bean). Run `mvn -q test -pl hybrid-search,hybrid-search-spring -am`. Expected: FAIL.
- [ ] **Step 4:** Implement both wirings: build `new CaseContextRetriever(caseRetriever)`, tenant supplier from `CurrentPrincipal`. Spring: `@ConditionalOnBean(CaseRetriever.class)`. Run again. Expected: PASS.
- [ ] **Step 5:** Commit: `feat(hybrid-search): neocortex-backed ContentRetriever (Closes #I9)`.

---

### Task 11: BOM, examples, docs, cross-repo follow-ups

**Files:**
- Modify: `bom/pom.xml`, `examples/pom.xml`, `examples/src/main/java/io/...` (replace the single stub class)
- Modify: `docs/guides/consumer-guide.md`, `docs/guides/contributor-guide.md`, `README.md`, `ARC42STORIES.MD`, `HANDOFF.md`
- Test: `examples/src/test/java/io/casehub/langchain4j/examples/{AuditExampleTest,TenancyExampleTest}.java`

**Interfaces:**
- Consumes: every module above.

- [ ] **Step 1:** Write `BomCoverageTest` in `examples` that parses `../bom/pom.xml` and `../pom.xml` and asserts every reactor module except `bom` and `examples` has a BOM entry. Run `mvn -q test -pl examples -am`. Expected: FAIL if any module is missing → fix `bom/pom.xml` → PASS.
- [ ] **Step 2:** Replace the example stub with two runnable Quarkus examples in the `examples` module, each with a `@QuarkusTest` over a stub `ChatModel` (no network): `AuditExampleTest` — one chat call produces one `AI_CHAT_COMPLETION` event in a recording `AuditEntryWriter`; `TenancyExampleTest` — two tenants, same `memoryId`, no leakage. Run until PASS.
- [ ] **Step 3:** Update the consumer guide: per module, the dependency snippet, what activates automatically, and what does not — the one-line agent-listener attachment (D8), the `casehubHybridRetriever` opt-in, the tenancy fail-closed rule, id-based embedding removal not being tenant-checked, and the parallel-sub-agent lineage gap.
- [ ] **Step 4:** In `ARC42STORIES.MD` set C1–C5 to done in §9.3 and §9.4, replace their `TBD` issue lines with the real issue numbers, and rewrite §6 runtime views and §12 "Platform module migration" to match D1, D3–D7. Mark the "Must do" items in `HANDOFF.md` that are now complete.
- [ ] **Step 5:** Run `mvn clean install`. Expected: BUILD SUCCESS, zero skipped modules.
- [ ] **Step 6:** Open follow-up issues (do not fix from this repo): `casehubio/parent` — remove BOM entries `casehub-platform-agent-langchain4j`, `casehub-platform-agent-langchain4j-core` and any `casehub-langchain4j-agents*`; `casehub-ops` — rewrite the three `topology-tests` classes that import `AgentProviderChatModel` / `CaseHubChatResponseMetadata` to call `AgentProvider` directly; `claudony`, `quarkmind` — drop the unused `casehub-platform-agent-langchain4j` dependency; `casehubio/engine` — correct the Phase 1 spec per the decisions table above.
- [ ] **Step 7:** Commit: `docs: BOM, examples, guides and ARC42 status for Phase 1 core (Closes #I10, Closes #E)`.

---

## Dependency order

```
Task 1 ─┬─ Task 2 (independent)
        ├─ Task 3 → Task 4 → Task 5 ─┐
        │                └──→ Task 8 ─┴→ Task 9
        ├─ Task 6 → Task 7
        └─ Task 10
all → Task 11
```

Tasks 2, 3–5, 6–7 and 10 touch disjoint modules and can run in parallel after Task 1. Task 8 needs Task 4; Task 9 needs Tasks 5 and 8.
