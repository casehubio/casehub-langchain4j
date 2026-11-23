# Retrieval Tracking at the Augmentor — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** One retrieval record per question when the application's `RetrievalAugmentor` rewrites queries, with automatic wiring in Spring and Quarkus.

**Architecture:** `TrackingRetrievalAugmentor` (tracking-core) wraps the augmentor, marks the request's `InvocationParameters`, and records once with the user's text and the aggregated contents; `TrackingContentRetriever` stands down when it sees the marker. Recording logic moves to one shared `RetrievalRecorder`. Spring's post-processor and Quarkus's `@Dependent` producer + `Supplier<RetrievalAugmentor>` decorator wrap augmentors automatically.

**Tech Stack:** Java 21, LangChain4j 1.19.0, neocortex rag-api, Quarkus 3.39.3 (ArC), Spring Boot 4.1.1, JUnit 5, AssertJ.

**Spec:** `docs/superpowers/specs/2026-11-23-augmentor-tracking-design.md` (issue #28)

## Global Constraints

- Marker key: `TrackingRetrievalAugmentor.TRACKED_BY_AUGMENTOR_KEY = "casehub.langchain4j.tracking.augmentor"`; value an `AtomicInteger` counter, changed under `synchronized` on the `InvocationParameters`.
- Recorded query text = `((UserMessage) request.chatMessage()).singleText()`; non-`UserMessage` → pass through, no record.
- Recording rules identical to `TrackingContentRetriever` today (tenant blank → no record; `source_document_id`; score precedence; non-empty with no document IDs → no record; empty → zero-hit record).
- Augmentor upstream rule: record only contents without `retrieval_id`/`_trackingId`; copy `_trackingId` → `retrieval_id` on the others; all upstream → no record. Retriever keeps all-or-nothing.
- Tracking failure → WARN + delegate result unchanged; delegate failure propagates after the marker is released.
- Quarkus priority `TrackingBeans.PRIORITY = 300`; Spring `POST_PROCESSOR_ORDER` unchanged.
- Every commit: `Refs #28` (last one `Closes #28`) and `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Stage by explicit path.

## Review Focus

1. Router sending one query to two wrapped retrievers → one record holding both retrievers' contents (Task 2: `routerToTwoRetrievers_oneRecord`).
2. Aggregator returns nothing → one zero-hit record, retrievers still silent (Task 2: `emptyAggregation_zeroHitRecord`).
3. Tenant blank at the augmentor → no record anywhere (retrievers must not fall back to recording) (Task 2: `blankTenant_noRecordAnywhere`).
4. Delegate returns a result with `contents() == null` → returned unchanged, no NPE (Task 2: `nullContents_passThrough`).
5. A `Supplier<RetrievalAugmentor>` bean returning the already-tracked `RetrievalAugmentor` bean → not wrapped twice (Task 4: `supplierReturningTrackedBean_notWrappedTwice`).

---

### Task 1: Spike — verify the two Quarkus mechanisms (throwaway, not committed)

**Files:** scratch test classes under `tracking/src/test/java/io/casehub/langchain4j/tracking/quarkus/spike/` — deleted at the end of the task.

- [ ] **Step 1:** Try adding test-scope `io.quarkiverse.langchain4j:quarkus-langchain4j-core` (1.14.1, available locally) to `tracking/pom.xml` and start a `@QuarkusTest` with one `@RegisterAiService` interface (default `retrievalAugmentor`) and a test `ChatModel` bean that echoes. If Quarkus 3.39.3 refuses it, ledger a Ruling and verify instead by reproducing the recorder's lookups: `Instance<RetrievalAugmentor>` (`isResolvable()` then `get()`) and `Arc.container().instance(SupplierBean.class).get().get()`.
- [ ] **Step 2:** Mechanism A — a test `@Produces @Alternative @Priority(300) @Dependent RetrievalAugmentor` returning `null`, no other augmentor bean. Expected: application starts; `Instance<RetrievalAugmentor>.isResolvable()` is `true` and `get()` is `null`; with quarkus-langchain4j, the AI service answers (no augmentation).
- [ ] **Step 3:** Mechanism B — an `@ApplicationScoped class SpikeSupplier implements Supplier<RetrievalAugmentor>` and a test `@Decorator @Priority(300)` on `Supplier<RetrievalAugmentor>` whose `get()` wraps the result in a marker class. Expected: the looked-up supplier returns the wrapper (with quarkus-langchain4j: `@RegisterAiService(retrievalAugmentor = SpikeSupplier.class)` uses it).
- [ ] **Step 4:** Both hold → ledger `Task 1: Ruling: mechanisms verified (<how>)`, delete the spike files and any pom change not needed by Task 4 (keep quarkus-langchain4j test dep only if Step 1 used it). Either fails → **stop and report to the owner** (spec).

### Task 2: Core — `RetrievalRecorder`, `TrackingRetrievalAugmentor`, retriever stand-down

**Files:**
- Create: `tracking-core/src/main/java/io/casehub/langchain4j/tracking/RetrievalRecorder.java`, `tracking-core/src/main/java/io/casehub/langchain4j/tracking/TrackingRetrievalAugmentor.java`
- Modify: `tracking-core/src/main/java/io/casehub/langchain4j/tracking/TrackingContentRetriever.java`, `tracking-core/pom.xml` (test dep `dev.langchain4j:langchain4j` only if `DefaultRetrievalAugmentor` is not in langchain4j-core — it is, so likely none)
- Test: `tracking-core/src/test/java/io/casehub/langchain4j/tracking/TrackingRetrievalAugmentorTest.java`; existing `TrackingContentRetrieverTest` stays green

**Interfaces:**
- Produces: `public class TrackingRetrievalAugmentor implements RetrievalAugmentor` — `public static final String TRACKED_BY_AUGMENTOR_KEY = "casehub.langchain4j.tracking.augmentor"`; ctor `(RetrievalAugmentor delegate, RetrievalTracker tracker, String corpusName, Supplier<String> tenantId, Consumer<RetrievalRecorded> recorded)`; `public RetrievalAugmentor delegate()`.
- Produces (package-private): `final class RetrievalRecorder` — ctor `(RetrievalTracker, String corpusName, Supplier<String> tenantId, Consumer<RetrievalRecorded>)`; `List<Content> record(String queryText, List<Content> contents, boolean recordUntrackedSubset)`; `static boolean markedByAugmentor(Query query)` (null-safe through `metadata()`, `invocationContext()`, `invocationParameters()`).

- [ ] **Step 1: Write the failing tests** in `TrackingRetrievalAugmentorTest`. Fixture: `DefaultRetrievalAugmentor` (executor `Runnable::run` unless stated) over `new TrackingContentRetriever(app, tracker, "kb", () -> "a", events::add)`, wrapped in `new TrackingRetrievalAugmentor(inner, tracker, "kb", () -> "a", events::add)`; `RecordingTracker`; request `new AugmentationRequest(UserMessage.from("how do I reset my password?"), Metadata.builder().chatMessage(msg).invocationContext(ctx).build())`; contents carry `source_document_id`.
  - `rewritingTransformer_oneRecordUnderUsersText` — transformer returns `Query.from("variant one", q.metadata())`, `Query.from("variant two", q.metadata())`: `tracker.records` size 1, its `query().text()` = `"how do I reset my password?"`, chunks = aggregated contents; all `result.contents()` have `retrieval_id` = `"r-1"`; `events` size 1.
  - `singleQuery_oneRecord` — no transformer: 1 record.
  - `nullInvocationParameters_stillOneRecord` — request built with `Metadata.from(msg, null, List.of())`: 1 record; the delegate (a lambda recording its request) saw the marker; its copied context keeps the caller's `chatMemoryId`.
  - `callerInvocationParameters_markerRemovedAfter` — caller `InvocationParameters.from("tenant-hint", "x")`: inside the delegate `containsKey(TRACKED_BY_AUGMENTOR_KEY)`; after `augment` returns it does not; `"tenant-hint"` still present.
  - `concurrentCallsSharingParameters_eachOneRecord` — two threads, one shared `InvocationParameters`, a latch so call A finishes while call B's retriever has not yet run: 2 records total, none from the retriever.
  - `transformerDroppingMetadata_retrieverRecordsAsToday` — transformer returns `Query.from("variant one")`, `Query.from("variant two")`: 2 records with variant texts, augmentor adds none for already-stamped contents.
  - `trackerFails_resultUnchanged` / `delegateFails_markerReleased` — tracker throws → result contents equal the delegate's, WARN; delegate throws → same exception, parameters no longer contain the key.
  - `nonUserMessage_passThrough` — `SystemMessage` request with a delegate stub: no record, result identical.
  - `partlyUpstream_recordsUntrackedOnly` — delegate stub returns `[content with _trackingId "t-9", plain doc-2]`: 1 record with only doc-2; first content `retrieval_id` = `"t-9"`, second `"r-1"`. `allUpstream_noRecordIdsCopied` — every content has `_trackingId`: 0 records, each gets its own as `retrieval_id`.
  - Review Focus: `routerToTwoRetrievers_oneRecord`, `emptyAggregation_zeroHitRecord` (aggregator `m -> List.of()` → 1 record, 0 chunks), `blankTenant_noRecordAnywhere` (augmentor and retriever tenant `() -> " "` → 0 records), `nullContents_passThrough` (stub result `AugmentationResult.builder().chatMessage(msg).build()`).
  - In `TrackingContentRetrieverTest`: `queryWithoutMetadata_recordedAsToday` (`Query.from("q")` → 1 record, no NPE).
- [ ] **Step 2:** Run `mvn -q test -pl tracking-core` → compile failure (classes missing).
- [ ] **Step 3:** Move the body of `TrackingContentRetriever.track(...)` into `RetrievalRecorder.record(text, contents, false)` (behaviour identical; WARN-once flag lives in the recorder); `TrackingContentRetriever.retrieve` returns the delegate's contents untouched when `RetrievalRecorder.markedByAugmentor(query)`. Run `TrackingContentRetrieverTest` → green before going on.
- [ ] **Step 4:** Implement `TrackingRetrievalAugmentor.augment` per spec §Design steps 1–4: counter marker on non-null `invocationParameters()`; otherwise copy request → `Metadata.builder()` with `chatMessage`, `systemMessage`, `chatMemory`, and an `InvocationContext.builder()` copying every field of the original context (`invocationId`, `interfaceName`, `methodName`, `methodArguments`, `userMessage`, `chatMemoryId`, `defaultRequestParameters`, `modelProvider`, `managedParameters`, `timestamp`) with `invocationParameters(InvocationParameters.from(KEY, new AtomicInteger(1)))`; record via `recorder.record(text, result.contents(), true)`; return `AugmentationResult.builder().chatMessage(result.chatMessage()).contents(stamped).build()`.
- [ ] **Step 5:** Run `mvn -q test -pl tracking-core` → all green.
- [ ] **Step 6: Commit** `tracking-core` sources and tests: `feat(tracking): record once per question at the augmentor` (`Refs #28`).

### Task 3: Spring — wrap `RetrievalAugmentor` beans

**Files:**
- Modify: `tracking-spring/src/main/java/io/casehub/langchain4j/tracking/spring/TrackingAutoConfiguration.java`
- Test: `tracking-spring/src/test/java/io/casehub/langchain4j/tracking/spring/TrackingAutoConfigurationTest.java`

**Interfaces:** Consumes `TrackingRetrievalAugmentor` (Task 2).

- [ ] **Step 1: Write the failing tests:**
  - `retrievalAugmentorBean_isWrapped` — context with a tracker bean and a `RetrievalAugmentor` bean (a `DefaultRetrievalAugmentor` with a two-variant transformer over a `ContentRetriever` bean): `context.getBean(RetrievalAugmentor.class)` is a `TrackingRetrievalAugmentor`; one `augment` → 1 record under the user's text; one `RetrievalRecorded` application event.
  - `noTracker_augmentorNotWrapped` — no tracker: the bean is the application's.
  - `trackingAugmentorBean_notWrappedAgain` — a bean already a `TrackingRetrievalAugmentor` stays as is.
- [ ] **Step 2:** Run `mvn -q test -pl tracking-spring -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=TrackingAutoConfigurationTest` → the new tests fail.
- [ ] **Step 3:** In `postProcessAfterInitialization`, a `RetrievalAugmentor` that is not a `TrackingRetrievalAugmentor` is wrapped with the same tracker lookup, corpus, `tenant()` supplier and event publisher as retrievers (extract the shared lookup; same INFO when no tracker). Update the class javadoc.
- [ ] **Step 4:** Run `mvn -q test -pl tracking-spring -am` → green.
- [ ] **Step 5: Commit** `feat(tracking-spring): wrap RetrievalAugmentor beans` (`Refs #28`).

### Task 4: Quarkus — `@Dependent` producer and `Supplier` decorator

**Files:**
- Modify: `tracking/src/main/java/io/casehub/langchain4j/tracking/quarkus/TrackingBeans.java`
- Create: `tracking/src/main/java/io/casehub/langchain4j/tracking/quarkus/TrackingAugmentorSupplierDecorator.java`
- Modify (only if Task 1 kept it): `tracking/pom.xml`
- Test: `tracking/src/test/java/io/casehub/langchain4j/tracking/quarkus/TrackingAugmentorTest.java` (+ test profiles / beans in the existing `TrackingTestBeans` style)

**Interfaces:**
- Consumes `TrackingRetrievalAugmentor` (Task 2); Task 1's ruling (how the AI-service path is exercised).
- Produces: `TrackingBeans#trackingRetrievalAugmentor(...)` — `@Produces @Alternative @Priority(PRIORITY) @Dependent RetrievalAugmentor`; package-private `RetrievalAugmentor track(RetrievalAugmentor delegate)` on `TrackingBeans` (tracker, tenant, corpus, event — used by the producer and the decorator).

- [ ] **Step 1: Write the failing tests** (one `@QuarkusTest` profile each, as the existing tests do):
  - `augmentorBean_resolvesToTracking` — app `RetrievalAugmentor` bean: injected `RetrievalAugmentor` is a `TrackingRetrievalAugmentor`; one augment with a two-variant transformer → 1 record.
  - `noAugmentorBean_nullAndAppStarts` — app with only a `ContentRetriever`: `Instance<RetrievalAugmentor>.get()` is `null`; existing retriever tracking still works (1 record per direct retrieve).
  - `supplierBean_getReturnsTracking` — `@ApplicationScoped Supplier<RetrievalAugmentor>` bean: `get()` returns a `TrackingRetrievalAugmentor`.
  - `supplierReturningTrackedBean_notWrappedTwice` — supplier whose `get()` returns the injected `RetrievalAugmentor`: result's `delegate()` is not a `TrackingRetrievalAugmentor`.
  - `noTracker_augmentorUnwrapped` — app augmentor, no tracker: the app's augmentor is injected.
  - If Task 1 kept quarkus-langchain4j: `aiService_recordsOncePerQuestion` through `@RegisterAiService` (bean path and supplier path).
- [ ] **Step 2:** Run `mvn -q test -pl tracking -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=TrackingAugmentorTest` → fail.
- [ ] **Step 3:** Producer: resolve the `RetrievalAugmentor` ArC would inject with the existing `narrow` rules (generalise `resolvedBean()` to a type parameter, excluding `TrackingBeans` beans); none → `null`; several → `AmbiguousResolutionException` (as for retrievers); no tracker → the app's bean; else `track(bean)`. Decorator: `@Decorator @Priority(TrackingBeans.PRIORITY) abstract class ... implements Supplier<RetrievalAugmentor>`, `@Inject @Delegate @Any Supplier<RetrievalAugmentor> delegate`, `@Inject TrackingBeans beans`; `get()` → result null or already `TrackingRetrievalAugmentor` → as is, else `beans.track(result)`.
- [ ] **Step 4:** Run `mvn -q test -pl tracking -am` → all tracking tests green (existing ones included).
- [ ] **Step 5: Commit** `feat(tracking): track Quarkus retrieval augmentors` (`Refs #28`).

### Task 5: Docs and full build

**Files:** `docs/guides/consumer-guide.md` (tracking section), `docs/superpowers/specs/2026-11-20-retrieval-tracking-design.md` (known limits), `ARC42STORIES.MD` (C9 key classes, issue #28).

- [ ] **Step 1:** Consumer guide: rewriting augmentors (one record per question, user's text, aggregated contents; `retrieval_id` from `Result.sources()`); automatic wrapping (Spring bean; Quarkus bean or `Supplier` bean); hand-wrapping a non-bean augmentor (`new TrackingRetrievalAugmentor(...)` example); the metadata caveat (a transformer that drops query metadata → per-variant records); CRAG grades against the rewritten query, by design.
- [ ] **Step 2:** Retrieval tracking spec: a known-limits line pointing to this design (#28). ARC42 C9: `TrackingRetrievalAugmentor`, issue #28.
- [ ] **Step 3:** Run `mvn clean install` → BUILD SUCCESS, 0 failures.
- [ ] **Step 4: Commit** `docs(tracking): retrieval tracking at the augmentor` (`Closes #28`).
