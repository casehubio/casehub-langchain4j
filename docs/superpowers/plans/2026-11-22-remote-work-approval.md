# Remote work approvals (Spring) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Spring applications get oversight-gate approvals from a running casehub-work service over REST.

**Architecture:** `WorkItemApprovalPort` moves onto a small `WorkItemGateway`. There are two implementations: in-process over `WorkItemApi` (unchanged behaviour) and `HttpWorkItemGateway` over casehub-work's generated REST resource. governance-spring picks the remote gateway when there is no `WorkItemApi` bean and `casehub.langchain4j.governance.work.url` is set.

**Tech Stack:** Java 21, JDK `HttpClient`, Jackson 2 (already in governance-core), Spring Boot 4.1.1, JUnit 5, AssertJ, JDK `com.sun.net.httpserver.HttpServer` for tests.

**Spec:** `docs/superpowers/specs/2026-11-22-remote-work-approval-design.md`

## Global Constraints

- No new runtime dependencies in governance-core. `Instant` is (de)serialised by a small Jackson `SimpleModule` (`Instant::toString` / `Instant.parse`), not jsr310.
- `casehub-work-api` stays `optional` in governance-core and governance-spring.
- REST base path is `<url>/api/work/items/`. The endpoints are `list-all`, `create` and `add-label/{id}`.
- Property prefix is `casehub.langchain4j.governance.work`, with keys `url`, `token`, `tenancy-id` and `timeout` (default `10s`).
- A token never appears in logs or exception messages. Exceptions carry the status code, not the body.
- Build: `mvn -q test -pl governance-core` / `-pl governance-spring` (run `mvn -q install -DskipTests -pl governance-core` before the Spring module).
- Commits reference `Refs #15`, and the last one `Closes #15`. Stage files by explicit path.

## Review Focus

- **The token for a different tenant.** A call for tenant B must never use A's token, including with `WorkTokenProvider.single`. Test: `single` returns empty for another tenant, and the gateway throws without a request.
- **A partly approved state over HTTP.** An APPROVED item already labelled `used` must not run twice. The `labels[].path` parsing feeds `used`. Test: an item with the `used` label maps to `labels` containing it, and the port does not run.
- **Pagination ending early.** The server answers `hasMore=true` with an empty `items`. It must terminate, not loop. Test in Task 2.
- **Query encoding.** Labels contain `/`, and fingerprints are hex. `appliedBy` contains `:`. Test: the server sees the exact decoded values.
- **Half-configured Spring.** A `url` without `token` or `tenancy-id` and with no provider bean must fail at startup, not refuse silently. Test in Task 3.

---

### Task 1: Gateway seam in governance-core

**Files:**
- Create: `governance-core/src/main/java/io/casehub/langchain4j/governance/gate/GateItem.java`
- Create: `governance-core/src/main/java/io/casehub/langchain4j/governance/gate/WorkItemGateway.java`
- Create: `governance-core/src/main/java/io/casehub/langchain4j/governance/gate/WorkItemApiGateway.java`
- Modify: `governance-core/src/main/java/io/casehub/langchain4j/governance/gate/WorkItemApprovalPort.java`
- Test: `governance-core/src/test/java/io/casehub/langchain4j/governance/gate/WorkItemApprovalPortTest.java`

**Interfaces:**
- Produces:
  - `record GateItem(UUID id, WorkItemStatus status, String createdBy, String callerRef, Instant createdAt, Set<String> labels, String candidateGroups)`. The compact constructor turns null `labels` into `Set.of()` and copies them.
  - `interface WorkItemGateway { List<GateItem> findByLabel(String label, String tenancyId); GateItem create(WorkItemCreateRequest request, String tenancyId); void addLabel(UUID id, String path, String appliedBy, String tenancyId); }`
  - `final class WorkItemApiGateway implements WorkItemGateway`, with constructor `(WorkItemApi api)` and `static GateItem toGateItem(WorkItemView view)`.
  - `WorkItemApprovalPort(WorkItemGateway gateway)`. The existing `WorkItemApprovalPort(WorkItemApi api)` delegates with `new WorkItemApiGateway(api)`.

- [ ] **Step 1: Write the failing test** `portOverGateway_usesOnlyGenuineNewestItem`. It uses an in-test `WorkItemGateway` lambda/anonymous class returning three `GateItem`s for the label:
  1. a genuine COMPLETED item at t1;
  2. a genuine REJECTED item at t2 > t1;
  3. a forged item at t3 (callerRef wrong, `createdBy="user:x"`).

  Assert that `latest(...)` gives the t2 id with state `REJECTED`. Also add `portOverGateway_usedLabelMarksUsed`: a genuine COMPLETED item whose labels contain `USED_LABEL` gives `used() == true`.
- [ ] **Step 2: Run** `mvn -q test -pl governance-core -Dtest=WorkItemApprovalPortTest`. Expected: compilation FAIL (`WorkItemGateway`/`GateItem` missing).
- [ ] **Step 3: Implement.** Paging (`PAGE = 50`, stop on `!hasMore || items.isEmpty()`) moves into `WorkItemApiGateway.findByLabel`. The port's `latest` filters `GateItem`s with the existing genuineness rule. `toRequest` reads `GateItem`. `create` calls `gateway.create(request, tenancy)`, and `markUsed` calls `gateway.addLabel(id, USED_LABEL, APPLIED_BY, tenancy)`. The class javadoc names both gateways.
- [ ] **Step 4: Run** `mvn -q test -pl governance-core`. Expected: PASS. All existing port, executor and end-to-end tests pass unchanged.
- [ ] **Step 5: Commit** `refactor(governance): approval port over a WorkItemGateway` (Refs #15).

### Task 2: HttpWorkItemGateway

**Files:**
- Create: `governance-core/src/main/java/io/casehub/langchain4j/governance/gate/WorkTokenProvider.java`
- Create: `governance-core/src/main/java/io/casehub/langchain4j/governance/gate/WorkItemGatewayException.java`
- Create: `governance-core/src/main/java/io/casehub/langchain4j/governance/gate/HttpWorkItemGateway.java`
- Create (test-jar, reused by Task 3): `governance-core/src/test/java/io/casehub/langchain4j/governance/gate/FakeWorkServer.java`
- Test: `governance-core/src/test/java/io/casehub/langchain4j/governance/gate/HttpWorkItemGatewayTest.java`

**Interfaces:**
- Consumes: `GateItem` and `WorkItemGateway` from Task 1, and `FakeWorkItemApi` (existing test class).
- Produces:
  - `@FunctionalInterface interface WorkTokenProvider { Optional<String> token(String tenancyId); static WorkTokenProvider single(String tenancyId, String token); }`. `single` requires both to be non-blank (`IllegalArgumentException` otherwise) and answers only for an equal `tenancyId`.
  - `class WorkItemGatewayException extends RuntimeException` with `int status()`, which is `-1` for I/O and timeout.
  - `HttpWorkItemGateway(URI baseUrl, WorkTokenProvider tokens, Duration timeout)`, which implements `WorkItemGateway`. A base URL with or without a trailing `/` behaves the same.
  - `FakeWorkServer implements AutoCloseable`:
    - constructor `(FakeWorkItemApi work, Map<String, String> tenantByToken)`;
    - `URI url()`;
    - `List<String> requests()` (method + raw path + query);
    - `void failNext(int status)` and `void delayNext(Duration)`;
    - `void forcePages(List<List<WorkItemView>> pages, boolean lastHasMore)`, for the early-end case.

    It serves the three endpoints by calling `work.api` with the tenant mapped from the bearer token, returns 401 for an unknown token, and uses the same Jackson + `Instant` module for JSON. The `add-label` endpoint returns 404 when the API returns null.

- [ ] **Step 1: Write the failing tests** in `HttpWorkItemGatewayTest`, each against a `FakeWorkServer`:
  - `findByLabel_followsHasMoreAcrossPages`: 60 matching items gives 60 `GateItem`s and two `list-all` requests with `offset=0` and `offset=50`, `limit=50`.
  - `findByLabel_stopsOnEmptyPageEvenIfHasMore`: forced pages `[[a]]` then `[]` with `hasMore=true` gives one item and terminates.
  - `findByLabel_mapsUsedLabelAndUnknownStatus`: an item with the `USED_LABEL` label is in `labels`. A raw JSON status `"SOMETHING_NEW"` gives `status == null`.
  - `create_postsRequestAndMaps201`: the server's `work.created` holds one request with the callerRef and the label path. The returned `GateItem.id` equals the stored id.
  - `addLabel_sendsDecodedQueryValues`: the server sees `path == "casehub-lc4j/gate/used"` and `appliedBy == "system:casehub-langchain4j"`.
  - `bearerTokenSelectsTenant`: with tokens `{"tA":"A","tB":"B"}` and a provider over both, the server's `work.listedTenants` contains `"B"` for a call with tenancy `B`.
  - `noTokenForTenant_throwsWithoutRequest`: `single("A","tA")` called for `B` throws `IllegalStateException`, and `requests()` is empty.
  - `serverError_throwsWithStatus`: `failNext(500)` gives `WorkItemGatewayException` with `status()==500`. The message does not contain the token.
  - `addLabel404_throws`: an unknown id gives status 404.
  - `slowResponse_timesOut`: with timeout 200ms and `delayNext(1s)` it throws `WorkItemGatewayException` with `status()==-1`.
- [ ] **Step 2: Run** `mvn -q test -pl governance-core -Dtest=HttpWorkItemGatewayTest`. Expected: compilation FAIL.
- [ ] **Step 3: Implement.**
  - Use one `HttpClient` per gateway with `connectTimeout(timeout)`, and give each request `.timeout(timeout)`.
  - Map private DTO records with `@JsonIgnoreProperties(ignoreUnknown = true)`. Read `status` as a `String` and map it to `WorkItemStatus` (an unknown value gives null).
  - Build the query with `URLEncoder.encode(v, UTF_8)`.
  - On `InterruptedException`, re-interrupt and throw a `WorkItemGatewayException(-1)`.
  - Expose the shared `ObjectMapper` setup as package-private `static ObjectMapper mapper()` so `FakeWorkServer` uses the same one.
- [ ] **Step 4: Run** `mvn -q test -pl governance-core`. Expected: PASS.
- [ ] **Step 5: Add** `remoteGate_endToEnd` to `WorkItemGateEndToEndTest`. It runs the existing gate flow with `WorkItemApprovalPort(new HttpWorkItemGateway(server.url(), single(...), 5s))`:
  1. The first call is pending.
  2. `work.setStatus(id, COMPLETED)`.
  3. The second call runs.
  4. A third call does not run again.

  Then add `remoteGate_serverErrorRefuses`: with `failNext(500)` the call is refused as unchecked. Run the module tests and expect PASS.
- [ ] **Step 6: Commit** `feat(governance): approvals over casehub-work REST` (Refs #15).

### Task 3: Spring wiring

**Files:**
- Modify: `governance-spring/src/main/java/io/casehub/langchain4j/governance/spring/GovernanceAutoConfiguration.java` (`WorkSupport`, the `oversightToolGate` javadoc and the WARN text)
- Test: `governance-spring/src/test/java/io/casehub/langchain4j/governance/spring/OversightToolGateAutoConfigurationTest.java`

**Interfaces:**
- Consumes: `HttpWorkItemGateway`, `WorkTokenProvider`, `WorkItemApprovalPort(WorkItemGateway)` and `FakeWorkServer` (test-jar).
- Produces:
  - constants `WORK_URL = "casehub.langchain4j.governance.work.url"`;
  - `WORK_TOKEN = "…work.token"`;
  - `WORK_TENANCY = "…work.tenancy-id"`;
  - `WORK_TIMEOUT = "…work.timeout"`.

- [ ] **Step 1: Write the failing tests:**
  - `remoteWork_fullGate`: properties url/token/tenancy-id (tenant matches the mocked `CurrentPrincipal`). The flow is pending, then `setStatus COMPLETED`, then the call runs, and the server saw `add-label`.
  - `workItemApiBeanWinsOverUrl`: with `WorkConfig` plus a url pointing at a closed port, the gate uses the bean (the call goes pending through `FakeWorkItemApi.created`).
  - `urlWithoutToken_failsStartup`: the context has `getStartupFailure()` with a message containing `WORK_TOKEN`, `WORK_TENANCY` and "WorkTokenProvider".
  - `tokenProviderBeanIsUsed`: a `WorkTokenProvider` bean and no token property make the gate work against the server.
  - `remoteServerError_refuses`: `failNext(500)` makes the call refused (the result starts with `OversightToolGate.REFUSAL_PREFIX`).
- [ ] **Step 2: Run** `mvn -q install -DskipTests -pl governance-core && mvn -q test -pl governance-spring -Dtest=OversightToolGateAutoConfigurationTest`. Expected: FAIL (no remote mode).
- [ ] **Step 3: Implement.**
  - `WorkSupport.approvalPort(beanFactory, environment)` reads the properties from the `Environment` (inject `Environment` into the bean method).
  - The timeout is bound with `Binder.get(env).bind(WORK_TIMEOUT, Duration.class).orElse(Duration.ofSeconds(10))`.
  - Log at INFO "remote casehub-work at <host>". The token is never logged.
  - The WARN for the no-port case mentions `WORK_URL`.
- [ ] **Step 4: Run** `mvn -q test -pl governance-spring`. Expected: PASS.
- [ ] **Step 5: Commit** `feat(governance-spring): remote casehub-work approvals` (Refs #15).

### Task 4: Docs and close

**Files:**
- Modify:
  - `docs/guides/consumer-guide.md` (governance / oversight gate section and the Spring limitation row);
  - `docs/superpowers/specs/2026-11-20-tool-oversight-gate-design.md` (Spring note);
  - `ARC42STORIES.MD` (#15 ✅).

- [ ] **Step 1: Edit the docs.** Cover the properties table, one token per tenant, the `WorkTokenProvider` example, the precedence (a bean wins over the URL), and the limitations (it needs a running service; there is no client-side tenant check; casehubio/work#436 is still pending for in-process).
- [ ] **Step 2: Check for stale lines.** Run `grep -rn "#15\|refuses gated\|no Spring WorkItemApi" docs README* */src/main` and fix any line that still says Spring always refuses.
- [ ] **Step 3: Run** `mvn -q test -pl governance-core,governance-spring`. Expected: PASS.
- [ ] **Step 4: Commit** `docs(governance): remote casehub-work approvals for Spring` with `Closes #15`.
