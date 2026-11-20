# Tool Oversight Gate Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Gate an agent's tool calls: classify each call with engine's `ActionRiskClassifier`, and run a `GateRequired` call only once a human approved that exact call in a casehub-work WorkItem.

**Architecture:** A `ToolExecutor` wrapper (`OversightToolGate`) sits between LangChain4j and the app's `@Tool` methods. It talks to two ports in `governance-core` — `RiskPort` (engine adapter) and `ApprovalPort` (casehub-work adapter) — both optional and touched only in holder classes. Refused calls return a tool result; decisions go into the audit lineage.

**Tech Stack:** Java 21, LangChain4j `1.19.0` (`langchain4j`, `langchain4j-core`), `langchain4j-agentic` `1.19.0-beta29`, `casehub-engine-api`, `casehub-worker-api`, `casehub-work-api`, Jackson, Quarkus `3.39.3`, Spring Boot `4.1.1`, JUnit 5, AssertJ, Mockito.

**Spec:** `docs/superpowers/specs/2026-11-20-tool-oversight-gate-design.md`

**Branch:** `feat/tool-oversight-gate`, cut from `feat/phase-1-core` (PR casehubio/casehub-langchain4j#14 is still open). Commits use `Refs #10` (core) / `Refs #11` (wiring); Spring's full gate is casehubio/casehub-langchain4j#15, out of scope.

## Global Constraints

- A tool runs only on `Autonomous`, or on an approved, not-yet-used WorkItem for the same fingerprint. Every other path — including every exception — returns a refusal and never runs the tool.
- Refusal results start with exactly `NOT PERFORMED: ` and name the tool, the reason and, when there is one, the WorkItem ID.
- Labels: `casehub-lc4j/gate/<fingerprint>` on each request; `casehub-lc4j/gate/used` once consumed. `callerRef` = `casehub-lc4j:gate:<fingerprint>`. Label persistence `MANUAL`, `appliedBy` = `system:casehub-langchain4j`.
- Fingerprint = lowercase hex SHA-256 of `tenant + "\n" + toolName + "\n" + canonicalJson(arguments)`; canonical JSON = keys sorted at every depth, no whitespace.
- Status mapping (engine `ActionGateCompletionApplier`): `COMPLETED` → approved; `PENDING`, `ASSIGNED`, `IN_PROGRESS`, `DELEGATED`, `SUSPENDED` → waiting; `REJECTED`, `CANCELLED`, `OBSOLETE` → rejected; `EXPIRED`, `FAULTED`, `ESCALATED` → no decision. Any other status → waiting.
- Classifiers are the `@RiskClassifier`-qualified `ActionRiskClassifier` beans, combined with `new ChainedActionRiskClassifier(list)`.
- Quorum, non-`StaticSetStrategy` or empty candidate groups → refuse (`could not check`).
- `casehub-engine-api`, `casehub-worker-api`, `casehub-work-api` are `optional` in every module; their types appear only in holder classes / adapters. Follow the contributor guide's "Optional CaseHub types" rule.
- Audit entries never contain tool arguments.

## Review Focus

1. **The model resends an approved call with the same arguments in a different key order or spacing** — it must match the approval. Test in Task 2 (`fingerprint_ignoresKeyOrderAndWhitespace`).
2. **Malformed or empty tool arguments** (`""`, `"not json"`) — `""` is treated as `{}`; invalid JSON refuses (`could not check`), never runs. Test in Task 4.
3. **An approval in tenant A, retried in tenant B** — must not run; a new request is created in B. Test in Task 4.
4. **An agent with gated and ungated tools** — an `Autonomous` call must not touch casehub-work at all. Test in Task 4.
5. **The tool throws after the approval was consumed** — the approval stays used (no silent re-use); the exception reaches LangChain4j unchanged. Test in Task 4.

---

### Task 1: Lineage hooks in `CasehubAgentListener`

**Files:**
- Modify: `audit-core/src/main/java/io/casehub/langchain4j/audit/CasehubAgentListener.java`
- Test: `audit-core/src/test/java/io/casehub/langchain4j/audit/CasehubAgentListenerTest.java`

**Interfaces:**
- Produces:
  - `public Optional<UUID> currentEntryId(Object memoryId)` — the innermost open invocation's entry ID; empty when nothing is open or the entry was not written.
  - `protected boolean toolPerformed(AfterAgentToolExecution after)` — default `true`. When it returns `false`, the `AGENT_TOOL_EXECUTED` event carries `"performed" -> false`; otherwise no `performed` key (existing entries unchanged).

- [ ] **Step 1: Failing tests** in `CasehubAgentListenerTest`:
  - `currentEntryId_isTheOpenInvocation` — after `before(parent)`, `before(child)`: equals `ids.get(1)`; after `after(child)`: equals `ids.get(0)`; after `after(parent)`: empty.
  - `currentEntryId_unknownMemoryId_isEmpty`.
  - `toolNotPerformed_marksEntry` — subclass overriding `toolPerformed` → `false`: `AGENT_TOOL_EXECUTED` data contains `performed=false`.
  - `toolPerformed_byDefault_noPerformedKey`.
- [ ] **Step 2:** `mvn -q test -pl audit-core -am -Dtest=CasehubAgentListenerTest` → FAIL (compilation).
- [ ] **Step 3:** Implement both methods; `currentEntryId` reuses the existing private lookup.
- [ ] **Step 4:** Same command → PASS; then `mvn -q test -pl audit-core -am` → PASS.
- [ ] **Step 5:** Commit `feat(audit): expose current lineage entry and tool-performed hook (Refs #10)`.

---

### Task 2: Gated call model and fingerprint

**Files:**
- Create: `governance-core/src/main/java/io/casehub/langchain4j/governance/gate/GatedAction.java`
- Create: `governance-core/src/main/java/io/casehub/langchain4j/governance/gate/GatedToolCall.java`
- Create: `governance-core/src/main/java/io/casehub/langchain4j/governance/gate/CallFingerprint.java`
- Modify: `governance-core/pom.xml` (add `dev.langchain4j:langchain4j`, `com.fasterxml.jackson.core:jackson-databind`; add optional `io.casehub:casehub-worker-api`, `io.casehub:casehub-work-api`)
- Test: `governance-core/src/test/java/io/casehub/langchain4j/governance/gate/CallFingerprintTest.java`

**Interfaces:**
- Produces:
  - `@Retention(RUNTIME) @Target(METHOD) public @interface GatedAction { String value(); }`
  - `public record GatedToolCall(String agentName, String toolName, String actionType, Map<String, Object> arguments, String tenancyId)`; compact constructor copies `arguments` (unmodifiable, insertion order kept).
  - `public final class CallFingerprint` with `static String of(String tenancyId, String toolName, Map<String, Object> arguments)` and `static Map<String, Object> parseArguments(String json)` — `null`/blank → empty map; invalid JSON or non-object → `IllegalArgumentException`.

- [ ] **Step 1: Failing tests** in `CallFingerprintTest`:
  - `fingerprint_isLowercaseHexSha256` — 64 chars, `[0-9a-f]`.
  - `fingerprint_ignoresKeyOrderAndWhitespace` — `parseArguments("{\"b\":1,\"a\":{\"y\":2,\"x\":3}}")` and `parseArguments("{ \"a\": {\"x\":3, \"y\":2}, \"b\": 1 }")` give equal fingerprints. *(Review Focus 1)*
  - `fingerprint_differsByTenant`, `…ByTool`, `…ByArgumentValue`.
  - `parseArguments_blank_isEmpty`; `parseArguments_invalid_throws`; `parseArguments_array_throws`.
- [ ] **Step 2:** `mvn -q test -pl governance-core -am -Dtest=CallFingerprintTest` → FAIL.
- [ ] **Step 3:** Implement with one shared `ObjectMapper` configured `ORDER_MAP_ENTRIES_BY_KEYS`; canonicalise by re-serialising the parsed tree with nested maps converted to `TreeMap`.
- [ ] **Step 4:** Same command → PASS.
- [ ] **Step 5:** Commit `feat(governance): gated tool call model and call fingerprint (Refs #10)`.

---

### Task 3: Ports and their engine / work adapters

**Files:**
- Create in `governance-core/src/main/java/io/casehub/langchain4j/governance/gate/`: `RiskPort.java`, `RiskVerdict.java`, `ApprovalPort.java`, `ApprovalRequest.java`, `ApprovalState.java`, `EngineRiskPort.java`, `WorkItemApprovalPort.java`
- Test: `governance-core/src/test/java/io/casehub/langchain4j/governance/gate/EngineRiskPortTest.java`, `WorkItemApprovalPortTest.java`

**Interfaces:**
- Consumes: `GatedToolCall` (Task 2).
- Produces:
  - `public interface RiskPort { RiskVerdict classify(GatedToolCall call); }`
  - `public sealed interface RiskVerdict { record Autonomous(); record Gate(String reason, boolean reversible, Set<String> candidateGroups, Duration expiresIn, String scope); record Unroutable(String reason); }`
  - `public enum ApprovalState { APPROVED, WAITING, REJECTED, NO_DECISION }`
  - `public record ApprovalRequest(UUID id, ApprovalState state, boolean used, Instant createdAt)`
  - `public interface ApprovalPort { Optional<ApprovalRequest> latest(String tenancyId, String fingerprint); ApprovalRequest create(GatedToolCall call, String fingerprint, RiskVerdict.Gate gate); void markUsed(ApprovalRequest request, String tenancyId); }`
  - `public EngineRiskPort(ActionRiskClassifier classifier)` — pass in the chain.
  - `public WorkItemApprovalPort(WorkItemApi api)`.

- [ ] **Step 1: Failing tests** — `EngineRiskPortTest` (classifier is a lambda; capture its arguments):
  - `buildsPlannedActionAndContext` — `PlannedAction(description = "Agent 'treasurer' calls tool 'freezeAccount'", actionType = "account.freeze", parameters = {accountId: "A1"})`; `ClassificationContext(workerId = "treasurer", caseId = null, tenancyId = "acme", caseDefinitionName = null, capabilityName = "freezeAccount", bindingName = null)`.
  - `autonomous_mapsToAutonomous`.
  - `staticGroups_mapToGate` — `StaticSetStrategy.of("mlro")`, `Duration.ofHours(24)`, scope `"aml"` → `Gate(reason, reversible, Set.of("mlro"), PT24H, "aml")`.
  - `quorum_isUnroutable`; `nullGroups_isUnroutable`; `nonStaticStrategy_isUnroutable` (a lambda `CandidateSetStrategy`); `emptyStaticGroups_isUnroutable`.
- [ ] **Step 2: Failing tests** — `WorkItemApprovalPortTest` (in-memory fake `WorkItemApi` storing `WorkItemView`s; unused methods throw):
  - `create_setsTitlePayloadGroupsExpiryLabelsCallerRefTenant` — title = reason; `candidateGroups` = `"mlro"`; `expiresAt` ≈ now + 24h; `scope`; `tenancyId`; `createdBy` = `"agent:treasurer"`; label `casehub-lc4j/gate/<fp>`; `callerRef` = `casehub-lc4j:gate:<fp>`; payload JSON has `agent`, `tool`, `actionType`, `arguments`, `reversible`.
  - `latest_picksNewestByCreatedAt`.
  - `statusMapping` — parameterised over every `WorkItemStatus` constant, asserting the Global Constraints table (unknown/other → `WAITING`).
  - `used_isTrueWhenUsedLabelPresent`.
  - `markUsed_addsUsedLabel`.
  - `latest_none_isEmpty`.
- [ ] **Step 3:** `mvn -q test -pl governance-core -am -Dtest='EngineRiskPortTest,WorkItemApprovalPortTest'` → FAIL.
- [ ] **Step 4:** Implement. `latest` = `api.listAll(null, null, "casehub-lc4j/gate/" + fp, null, tenancyId, 0, 50)`, newest `createdAt`.
- [ ] **Step 5:** Same command → PASS.
- [ ] **Step 6:** Commit `feat(governance): risk and approval ports with engine and casehub-work adapters (Refs #10)`.

---

### Task 4: `OversightToolGate`

**Files:**
- Create: `governance-core/src/main/java/io/casehub/langchain4j/governance/gate/OversightToolGate.java`, `GatedToolExecutor.java`
- Modify: `governance-core/src/main/java/io/casehub/langchain4j/governance/GovernanceAgentListener.java` (override `toolPerformed`)
- Modify: `audit-core/src/main/java/io/casehub/langchain4j/audit/AuditEvent.java` (constant `AGENT_TOOL_GATED`)
- Test: `governance-core/src/test/java/io/casehub/langchain4j/governance/gate/GatedToolExecutorTest.java`, `OversightToolGateRealAgentTest.java`

**Interfaces:**
- Consumes: Task 1 hooks; Tasks 2–3 types.
- Produces:
  - `public OversightToolGate(RiskPort riskOrNull, ApprovalPort approvalsOrNull, Supplier<String> tenancyId, AuditEntryWriter writer, CasehubAgentListener lineageOrNull)`
  - `public static final String REFUSAL_PREFIX = "NOT PERFORMED: ";`
  - `public ForAgent forAgent(String agentName)`; `ForAgent.wrap(Object... toolObjects) : Map<ToolSpecification, ToolExecutor>`; `ForAgent.wrap(Map<ToolSpecification, ToolExecutor>) : Map<ToolSpecification, ToolExecutor>`.
  - `GovernanceAgentListener.toolPerformed` returns `false` when the tool result text starts with `REFUSAL_PREFIX`.
  - Audit event `AGENT_TOOL_GATED`, data keys `toolName`, `decision` (`pending` | `rejected` | `approved` | `unchecked`), `workItemId` (when known), `reason`; `causedByEntryId` = `lineage.currentEntryId(memoryId)`; actor `agent:<name>`; subject as `CasehubAgentListener.subjectIdFor(memoryId)`.

Decision flow in `GatedToolExecutor.executeWithContext(request, context)` (and `execute` delegating with a null context):

| Condition | Result |
|---|---|
| `riskOrNull == null` | run tool |
| tenant blank, arguments invalid, classifier throws, `Unroutable` | refuse, `unchecked` |
| `Autonomous` | run tool, no approval-port call |
| `Gate`, `approvalsOrNull == null` | refuse, `unchecked` |
| `Gate`, latest absent / `NO_DECISION` / `APPROVED`+used | create, refuse `pending` |
| `Gate`, latest `WAITING` | refuse `pending` |
| `Gate`, latest `REJECTED` | refuse `rejected` |
| `Gate`, latest `APPROVED`, not used | `markUsed` (failure → refuse `unchecked`), run tool, audit `approved` |

Calls with the same fingerprint are serialised with a per-fingerprint lock (e.g. `ConcurrentHashMap<String, ReentrantLock>`) from lookup to `markUsed`.

- [ ] **Step 1: Failing tests** in `GatedToolExecutorTest` (fake `RiskPort`, in-memory fake `ApprovalPort`, a counting tool object with `@Tool @GatedAction("account.freeze") String freezeAccount(String accountId)` and an unannotated `@Tool String balance(String accountId)`), one test per table row, plus:
  - `actionType_fromAnnotation_elseToolName`.
  - `refusal_startsWithPrefix_namesToolReasonAndId`.
  - `approvedOnce_secondIdenticalCall_createsNewRequest` (one-shot).
  - `otherTenant_doesNotReuseApproval_createsRequestInItsTenant` *(Review Focus 3)*.
  - `autonomousTool_neverTouchesApprovalPort` *(Review Focus 4)*.
  - `blankArguments_areEmptyObject`; `invalidJsonArguments_refuseUnchecked_toolNotRun` *(Review Focus 2)*.
  - `toolThrowsAfterApproval_approvalStaysUsed_exceptionPropagates` *(Review Focus 5)*.
  - `concurrentIdenticalCalls_runToolOnce` — two threads, one approved request.
  - `auditEvent_chainsToCurrentInvocation_andHasNoArguments`.
- [ ] **Step 2: Failing test** `OversightToolGateRealAgentTest` — a real `AgenticServices.agentBuilder(...)` agent whose stub `ChatModel` first answers with a `freezeAccount` tool request and then with text; tools attached via `gate.forAgent("treasurer").wrap(tools)`; risk = gate, approvals = empty: assert the tool counter is 0, a WorkItem was created, the model's second request contains a tool result starting with `NOT PERFORMED: `, and the audit has `AGENT_TOOL_EXECUTED` with `performed=false` when `GovernanceAgentListener` is attached.
- [ ] **Step 3:** `mvn -q test -pl governance-core -am -Dtest='GatedToolExecutorTest,OversightToolGateRealAgentTest'` → FAIL.
- [ ] **Step 4:** Implement. `wrap(Object...)` builds pairs with `ToolSpecifications.toolSpecificationFrom(method)` and `new DefaultToolExecutor(object, method)` for each `@Tool` method; the `AgenticScope` (for `memoryId`) is the `managedParameters()` value that is an `AgenticScope`, else `context.chatMemoryId()`.
- [ ] **Step 5:** Same command → PASS; then `mvn -q test -pl governance-core,audit-core -am` → PASS.
- [ ] **Step 6:** Commit `feat(governance): OversightToolGate enforces approval on tool calls (Closes #10)`.

---

### Task 5: Quarkus and Spring wiring

**Files:**
- Modify: `governance/src/main/java/io/casehub/langchain4j/governance/quarkus/GovernanceBeans.java`, `governance/pom.xml`
- Modify: `governance-spring/src/main/java/io/casehub/langchain4j/governance/spring/GovernanceAutoConfiguration.java`, `governance-spring/pom.xml`
- Modify: `audit/src/main/resources/META-INF/microprofile-config.properties` (add `io.casehub.api.spi.ActionRiskClassifier`, `io.casehub.work.api.spi.WorkItemApi` to `quarkus.arc.unremovable-types`)
- Test: `governance/src/test/java/io/casehub/langchain4j/governance/quarkus/OversightToolGateBeansTest.java`, `OversightToolGateFullTest.java`; `governance-spring/src/test/java/io/casehub/langchain4j/governance/spring/OversightToolGateAutoConfigurationTest.java`

**Interfaces:**
- Consumes: Task 4 constructor.
- Produces: an `OversightToolGate` bean (Quarkus `@Singleton`, Spring `@ConditionalOnMissingBean`) built from: `@RiskClassifier ActionRiskClassifier` beans → `EngineRiskPort(new ChainedActionRiskClassifier(list))` (none → `riskOrNull = null`, INFO); a `WorkItemApi` bean → `WorkItemApprovalPort` (none while classifiers exist → WARN, `approvalsOrNull = null`); `CurrentPrincipal::tenancyId` (none → `() -> null`, WARN when classifiers exist); the `AuditEntryWriter` and `CasehubAgentListener` beans.
  - Quarkus: classifiers via `Arc.container().listAll(ActionRiskClassifier.class, new AnnotationLiteral<RiskClassifier>() {})` inside a holder.
  - Spring: beans of type `ActionRiskClassifier` whose bean type carries `@RiskClassifier` (`ListableBeanFactory.findAnnotationOnBean`), inside a holder.

- [ ] **Step 1: Failing tests**:
  - Quarkus `OversightToolGateBeansTest` (no CaseHub beans): gate injectable; wrapped tool runs (pass-through).
  - Quarkus `OversightToolGateFullTest` (test profile enabling a `@RiskClassifier` alternative that gates `account.freeze`, a fake `WorkItemApi` alternative, a principal): first call refused and one WorkItem created; complete it in the fake; retry runs the tool once.
  - Spring `OversightToolGateAutoConfigurationTest`: `noClassifiers_passThrough`; `classifierWithoutWorkItemApi_refusesGatedCalls_runsAutonomous`; `classifierAndWorkItemApi_fullGate`; `unqualifiedClassifierBean_isIgnored`; `userGateBean_wins`.
- [ ] **Step 2:** `mvn -q test -pl governance,governance-spring -am` → FAIL.
- [ ] **Step 3:** Implement both wirings.
- [ ] **Step 4:** Same command → PASS.
- [ ] **Step 5:** Commit `feat(governance): wire OversightToolGate for Quarkus and Spring (Refs #11)`.

---

### Task 6: Docs, status, full build

**Files:**
- Modify: `docs/guides/consumer-guide.md` (governance section: the gate, `@GatedAction`, the refusal result, the decision table, Spring limitation with link to #15, known limits from the spec)
- Modify: `ARC42STORIES.MD` (C4 → ✅ with gate on tool calls; §6 governance runtime view adds the gate), `README.md` (governance row), `docs/guides/contributor-guide.md` (ports + adapters), `HANDOFF.md`

- [ ] **Step 1:** Update the docs; `grep -rn "oversight gate needs redesign\|no gate\|cannot block" --include='*.md' --include='*.MD' .` → only historical mentions remain (the listener limitation stays documented as the reason for the tool gate).
- [ ] **Step 2:** `mvn clean install` → BUILD SUCCESS, 0 failures.
- [ ] **Step 3:** Commit `docs: tool oversight gate in guides and ARC42 (Refs #11)`.
