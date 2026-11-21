# Retrieval Tracking Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Record every retrieval of the application's `ContentRetriever` into neocortex's `RetrievalTracker`, stamp each content with `retrieval_id` and publish `RetrievalRecorded` — composing as Tracking(CRAG(Tenancy(app))) and never changing or failing a retrieval.

**Architecture:** `tracking-core` holds `TrackingContentRetriever` (pure Java, tenant as a `Supplier<String>`). `tracking` (Quarkus) wraps the bean the app would otherwise get via a priority-300 alternative producer; `tracking-spring` wraps via a `BeanPostProcessor` ordered after CRAG. Tenancy and CRAG stop warning about this library's own outranking producer.

**Tech Stack:** Java 21, LangChain4j `1.19.0`, `casehub-neocortex-rag-api`, `casehub-platform-api`, Quarkus `3.39.3`, Spring Boot `4.1.1`, JUnit 5, AssertJ, Mockito.

**Spec:** `docs/superpowers/specs/2026-11-20-retrieval-tracking-design.md`

**Branch / issue:** `feat/retrieval-tracking` (from `main`); commits reference `#19`.

## Global Constraints

- Segment metadata keys: `source_document_id` (read), `retrieval_id` (written, `TrackingContentRetriever.RETRIEVAL_ID_KEY`), `_trackingId` (neocortex's, read only).
- Score: `ContentMetadata.RERANKED_SCORE` if a finite number, else `ContentMetadata.SCORE` if finite, else `0.0` (never NaN — the SQLite schema rejects it).
- `maxResults` passed to `record` = number of contents returned.
- Tenant: `CurrentPrincipal` present → `tenancyId()` per retrieval, blank → not recorded (one WARN); no `CurrentPrincipal` bean → `"default"` (one INFO).
- Corpus: property `casehub.langchain4j.tracking.corpus`, default `default`, both frameworks.
- Tracking failures never reach the caller; delegate exceptions propagate unchanged.
- No `RetrievalTracker` bean → nothing wrapped, one INFO, never a startup failure. The tracker is **not** a dependency of these modules (not even transitively).
- Quarkus priority `TrackingBeans.PRIORITY = 300`; Spring `TrackingAutoConfiguration.POST_PROCESSOR_ORDER = Ordered.LOWEST_PRECEDENCE - 50`.
- Quarkus modules inject, never look up; `casehub-neocortex-rag-api` and `casehub-platform-api` are required dependencies of `tracking` and `tracking-spring`.
- Stage files by explicit path only (local tool files are never committed). Do not edit `CLAUDE.md`.

## Review Focus

1. **Content whose segment metadata already has a non-string `retrieval_id`/`_trackingId`** (e.g. a UUID object) — still treated as already tracked; `_trackingId` copied as its string form. Test in Task 2 (`trackingIdNonString_copiedAsString`).
2. **Same `source_document_id` on several contents** — recorded once with the max score (event's `documents` has one ref), every content stamped. Test in Task 2 (`duplicateDocument_collapsedInEvent_allStamped`).
3. **Contents with a null text segment** — returned untouched, not stamped, not recorded, no exception. Test in Task 2 (`contentWithoutSegment_returnedUntouched`).
4. **Tenant supplier throws** (principal bean misbehaves) — treated as a tracking failure: original contents returned. Test in Task 2 (`tenantSupplierThrows_returnsOriginal`).
5. **Tracking + CRAG + tenancy in Quarkus without a tracker** — the app still gets CRAG(Tenancy(app)). Test in Task 3 (`TrackingWithoutTrackerTest`).

---

### Task 1: Tenancy and CRAG ignore this library's producers in the outranking warning

**Files:**
- Modify: `tenancy/src/main/java/io/casehub/langchain4j/tenancy/quarkus/TenancyBeans.java` (`narrowLikeArc` warning), `crag/src/main/java/io/casehub/langchain4j/crag/quarkus/CragBeans.java` (`narrow` warning)
- Test: `tenancy/src/test/java/io/casehub/langchain4j/tenancy/quarkus/TenancyBeanSelectionTest.java`, `crag/src/test/java/io/casehub/langchain4j/crag/quarkus/CragBeanSelectionTest.java`

**Interfaces:**
- Produces: a bean is "this library's decorating producer" when its bean class is non-null, its package starts with `io.casehub.langchain4j` and its simple name ends with `Beans`. Such beans are still excluded from delegate selection when they outrank, but produce no WARN.

- [ ] **Step 1: Failing tests** (existing mock-bean helpers; capture JBoss log output with a JUL handler on the module's logger, as earlier tests in this repo do):
  - `libraryProducerAboveUs_notWarned` — mocked alternative, priority 300, `@Default`, bean class `io.casehub.langchain4j.tracking.quarkus.TrackingBeans` stand-in (use a nested test class whose name ends in `Beans` in a package under `io.casehub.langchain4j`; the test's own package qualifies) → excluded from the result, no WARN logged.
  - `applicationAlternativeAboveUs_stillWarned` — bean class `com.example.AppRetriever`-like (a class outside `io.casehub.langchain4j`, e.g. `java.lang.Object`) → WARN logged.
- [ ] **Step 2:** `mvn -q test -pl tenancy,crag -am` → FAIL (library producer warned).
- [ ] **Step 3:** Add the predicate to both warnings (copy, keep the two in step, comment says so).
- [ ] **Step 4:** Same command → PASS; existing tests pass.
- [ ] **Step 5:** Commit `fix(tenancy,crag): no outranking warning for this library's own decorators (Refs #19)`.

---

### Task 2: `tracking-core`

**Files:**
- Create: `tracking-core/pom.xml` (deps: `langchain4j-core`, `casehub-neocortex-rag-api`, `jboss-logging`; test: junit, assertj; build a **test-jar**), `tracking-core/src/main/java/io/casehub/langchain4j/tracking/TrackingContentRetriever.java`, test-jar class `tracking-core/src/test/java/io/casehub/langchain4j/tracking/RecordingTracker.java`
- Modify: root `pom.xml` (`<module>tracking-core</module>` after `crag-spring`; dependencyManagement for `casehub-langchain4j-tracking-core` and its `test-jar`)
- Test: `tracking-core/src/test/java/io/casehub/langchain4j/tracking/TrackingContentRetrieverTest.java`

**Interfaces:**
- Produces:
  - `public TrackingContentRetriever(ContentRetriever delegate, RetrievalTracker tracker, String corpusName, Supplier<String> tenantId, Consumer<RetrievalRecorded> recorded)`; `public ContentRetriever delegate()`; `public static final String RETRIEVAL_ID_KEY = "retrieval_id"`.
  - Test-jar `RecordingTracker implements RetrievalTracker`: public fields `List<Recorded> records` (`record Recorded(RetrievalQuery query, CorpusRef corpus, List<RetrievedChunk> chunks, int maxResults, String id)`), `RuntimeException failure` (thrown by `record` when set); `record` returns `"r-" + n` (1-based); other methods throw `UnsupportedOperationException`.

- [ ] **Step 1: Failing tests** (delegate = lambda returning a fixed list; tenant supplier = `() -> "a"`; corpus `"kb"`; events into a list):
  - `recordsQueryTenantCorpusAndDocuments` — contents `[doc-1 SCORE 0.8, doc-2 SCORE 0.5]` → one record: query text `"q"`, `CorpusRef("a","kb")`, chunk IDs `[doc-1, doc-2]`, scores `[0.8, 0.5]`, `maxResults` 2.
  - `scorePrecedence` — RERANKED 0.9 + SCORE 0.4 → 0.9; SCORE only → 0.4; RERANKED NaN + SCORE 0.4 → 0.4; neither → 0.0.
  - `allContentsReturnedInOrder_stampedWithRetrievalId` — includes a content without `source_document_id`: returned, stamped `r-1`, not in the record; original segment metadata and content metadata kept.
  - `missingSourceDocumentId_warnsOnce` — two retrievals with such content → one WARN.
  - `eventPublished` — `RetrievalRecorded("r-1", query, corpus, refs)`.
  - `duplicateDocument_collapsedInEvent_allStamped` *(Review Focus 2)*.
  - `emptyResult_recordedWithNoChunks` — record with empty chunks, `maxResults` 0, event published, returns empty list.
  - `nonEmptyWithoutAnySourceDocumentId_notRecorded` — tracker untouched, contents returned unstamped.
  - `alreadyStamped_anyContent_notRecorded` — one of two contents has `retrieval_id` → no record, contents unchanged.
  - `neocortexTrackingId_copiedToRetrievalId_notRecorded` — content with `_trackingId = "t-9"` → no record; returned with `retrieval_id = "t-9"`.
  - `trackingIdNonString_copiedAsString` *(Review Focus 1)*.
  - `blankQueryText_notRecorded`; `nullTenant_notRecorded` (supplier returns null) — contents unchanged.
  - `contentWithoutSegment_returnedUntouched` *(Review Focus 3)* — if `Content` cannot be built without a segment in LC4j 1.19.0, assert the mapping code null-checks via a mocked `Content` whose `textSegment()` returns null.
  - `trackerThrows_returnsOriginal_noEvent`; `eventConsumerThrows_returnsStamped`; `tenantSupplierThrows_returnsOriginal` *(Review Focus 4)*.
  - `delegateThrows_propagates`.
- [ ] **Step 2:** `mvn -q test -pl tracking-core -am` → FAIL (compilation).
- [ ] **Step 3:** Implement per the spec's numbered steps 1–8 and failure isolation; rebuild contents with `Content.from(TextSegment.from(text, metadata.copy().put(RETRIEVAL_ID_KEY, id)), content.metadata())`; WARN-once with an `AtomicBoolean` per instance.
- [ ] **Step 4:** Same command → PASS.
- [ ] **Step 5:** Commit `feat(tracking): TrackingContentRetriever records retrievals into RetrievalTracker (Refs #19)`.

---

### Task 3: `tracking` (Quarkus)

**Files:**
- Create: `tracking/pom.xml` (deps: `tracking-core`, `casehub-neocortex-rag-api`, `casehub-platform-api`, `quarkus-arc`; test: `quarkus-junit`, `tracking-core` test-jar, `casehub-langchain4j-tenancy`, `casehub-langchain4j-crag`; jandex plugin — mirror `crag/pom.xml`), `tracking/src/main/java/io/casehub/langchain4j/tracking/quarkus/TrackingBeans.java`
- Modify: root `pom.xml` (`<module>tracking</module>`, dependencyManagement)
- Test: `tracking/src/test/java/io/casehub/langchain4j/tracking/quarkus/{TrackingBeansTest,TrackingWithCragAndTenancyTest,TrackingWithoutTrackerTest,TrackingSeveralTrackersTest,TrackingWithoutPrincipalTest,TrackingBeanSelectionTest}.java`, `TrackingTestBeans.java`, `Unwrap.java` (copy of crag's)

**Interfaces:**
- Consumes: Task 1 behaviour; Task 2 types and `RecordingTracker`.
- Produces: `@Produces @Alternative @Priority(TrackingBeans.PRIORITY) @ApplicationScoped ContentRetriever trackingContentRetriever(@Any Instance<ContentRetriever> keepAlive, Instance<RetrievalTracker> tracker, Instance<CurrentPrincipal> principal, Event<RetrievalRecorded> recorded, @ConfigProperty(name = "casehub.langchain4j.tracking.corpus", defaultValue = "default") String corpus)`; delegate chosen as `CragBeans` does (exclude `TrackingBeans`, ignore alternatives with priority > 300, ArC rules — copy `narrow` as package-private static, with the Task 1 predicate). Ambiguous tracker → `AmbiguousResolutionException`; none → return delegate, INFO.

- [ ] **Step 1: Failing tests** (test beans: app retriever returning tenant `a` contents `good-a`/`bad-a` and tenant `b` `good-b`, each with `source_document_id`; `@Alternative @Singleton` tracker extending `RecordingTracker`, a second tracker, `TenantA` principal and `FakeEvaluator` from the crag pattern, an observer of `RetrievalRecorded`; enable via test profiles):
  - `TrackingBeansTest` (tracker only): retrieval recorded under tenant `default` *(no principal)*, corpus from `%test` profile property `kb`, contents stamped, observer got one event.
  - `TrackingWithCragAndTenancyTest` (tracker, evaluator, principal `a`): returned `["good-a"]`; the record has only `good-a`'s document under tenant `a`; first retrieval terminates; unwrap chain `TrackingContentRetriever` → `CragContentRetriever` → `TenantIsolatingContentRetriever`; no "outranks" WARN logged.
  - `TrackingWithoutTrackerTest` (evaluator, principal, no tracker): injected retriever unwraps to `CragContentRetriever` *(Review Focus 5)*.
  - `TrackingSeveralTrackersTest`: two trackers enabled at the same priority → startup/injection fails with `AmbiguousResolutionException` mentioning `RetrievalTracker`.
  - `TrackingWithoutPrincipalTest`: an enabled principal returning blank `tenancyId()` → tracker untouched, contents returned.
  - `TrackingBeanSelectionTest` (mock beans, like `CragBeanSelectionTest`): CRAG alternative (200) chosen over app bean; an application alternative at 400 is excluded and warned.
- [ ] **Step 2:** `mvn -q test -pl tracking -am` → FAIL.
- [ ] **Step 3:** Implement `TrackingBeans`; tenant supplier: principal resolvable → `() -> principal.get().tenancyId()` (blank → null, WARN once), otherwise `() -> "default"` with one INFO.
- [ ] **Step 4:** Same command → PASS.
- [ ] **Step 5:** Commit `feat(tracking): Quarkus wiring wrapping the resolved ContentRetriever (Refs #19)`.

---

### Task 4: `tracking-spring`

**Files:**
- Create: `tracking-spring/pom.xml` (deps: `tracking-core`, `casehub-platform-api`, `spring-boot-autoconfigure`; test: `spring-boot-test`, `tracking-core` test-jar, `casehub-langchain4j-tenancy-spring`, `casehub-langchain4j-crag-spring`), `tracking-spring/src/main/java/io/casehub/langchain4j/tracking/spring/TrackingAutoConfiguration.java`, `tracking-spring/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Modify: root `pom.xml`
- Test: `tracking-spring/src/test/java/io/casehub/langchain4j/tracking/spring/TrackingAutoConfigurationTest.java`

**Interfaces:**
- Consumes: `CragAutoConfiguration.POST_PROCESSOR_ORDER`, `TenancyAutoConfiguration.POST_PROCESSOR_ORDER` (tests only); Task 2 types.
- Produces: `@AutoConfiguration @ConditionalOnClass({ContentRetriever.class, RetrievalTracker.class})`; `static` `BeanPostProcessor` bean implementing `Ordered` with `POST_PROCESSOR_ORDER`; skips beans that are not `ContentRetriever`, are `TrackingContentRetriever`, are `RetrievalTracker`, or are named `"casehubHybridRetriever"` (string constant — hybrid-search is not a dependency); tracker via `ObjectProvider<RetrievalTracker>` (`NoUniqueBeanDefinitionException` → `IllegalStateException` naming `RetrievalTracker`); principal via `ObjectProvider<CurrentPrincipal>` read per retrieval (absent → `"default"`, INFO once); corpus from `Environment` property `casehub.langchain4j.tracking.corpus` (default `default`); events through `ObjectProvider<ApplicationEventPublisher>`.

- [ ] **Step 1: Failing tests** (`ApplicationContextRunner`, `RecordingTracker` bean):
  - `wrapsRetriever_records_publishesEvent` — record under tenant `default`, corpus `default`; with `withPropertyValues("casehub.langchain4j.tracking.corpus=kb")` corpus `kb`; `RetrievalRecorded` received by a listener bean.
  - `noTracker_leavesRetrieverUnwrapped`.
  - `severalTrackers_failsClearly`.
  - `alreadyTracking_notRewrapped`.
  - `blankTenancy_notRecorded` — a `CurrentPrincipal` bean with blank `tenancyId()` → tracker untouched, contents returned.
  - `hybridBean_notWrapped` — a `ContentRetriever` bean named `casehubHybridRetriever` stays as is.
  - `withCragAndTenancy_nestsTrackingOutside` — chain `TrackingContentRetriever` → `CragContentRetriever` → `TenantIsolatingContentRetriever`; record contains only the current tenant's CRAG-surviving documents, tenant from the principal.
- [ ] **Step 2:** `mvn -q test -pl tracking-spring -am` → FAIL.
- [ ] **Step 3:** Implement the auto-configuration and imports file (mirror `CragAutoConfiguration`).
- [ ] **Step 4:** Same command → PASS.
- [ ] **Step 5:** Commit `feat(tracking): Spring auto-configuration (Refs #19)`.

---

### Task 5: BOM, docs, follow-up issue, full build

**Files:**
- Modify: `bom/pom.xml` (three entries — `BomCoverageTest` enforces it), `docs/guides/consumer-guide.md` (new "tracking" module section after "crag": what it records; adding `casehub-neocortex-rag-tracking` + `casehub.rag.tracking.sqlite.path` on Quarkus, a `RetrievalTracker` bean on Spring; `source_document_id` must be set at ingestion; tenant/corpus rules; feedback via `retrieval_id`; `RetrievalAnalyzer` example; hybrid-search tracked by neocortex itself; known limits from the spec), `docs/guides/contributor-guide.md` (module table rows; composition order Tracking(CRAG(Tenancy)) and the priorities 100/200/300), `ARC42STORIES.MD` (C9 ✅, key class `TrackingContentRetriever`, issue #19, Spring tracker TODO issue), `README.md` (module row)

- [ ] **Step 1:** Create the follow-up issue "Spring-friendly SQLite RetrievalTracker" (`gh issue create`, body per the spec's *Spring tracker* section, "Part of #3"); put its number into the consumer guide, ARC42 and the spec's TODO.
- [ ] **Step 2:** Update the files above.
- [ ] **Step 3:** `mvn clean install` → BUILD SUCCESS, 0 failures.
- [ ] **Step 4:** Commit `docs: retrieval tracking in BOM, guides and ARC42 (Closes #19)` — include the spec's TODO edit.
