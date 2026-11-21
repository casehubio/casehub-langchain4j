# Retrieval tracking — design

**Date:** 2026-11-20 · **Issue:** #19 · **Chapter:** ARC42 C9 (Phase 1 extended) · **Branch:** `feat/retrieval-tracking` (from `main`)

## Intent

A RAG application cannot see what its retrieval does: which documents answer which questions,
which documents are never found, which ones keep coming back but do not help. Without that record,
curating a corpus is guesswork. LangChain4j has no retrieval observability.

neocortex already records this for its own `CaseRetriever` pipeline
(`docs/specs/2026-07-05-retrieval-tracking-spi-design.md` and
`docs/specs/2026-07-19-retrieval-tracking-analysis-design.md` in neocortex): a `RetrievalTracker`
stores each retrieval (query, corpus, documents with scores) and later feedback on whether a
document helped; `RetrievalAnalyzer` turns those records into per-document statistics, never-
retrieved documents and quality signals. This module records the retrievals of **any** LangChain4j
`ContentRetriever` into that same tracker, as ARC42 C9 describes, so the neocortex analysis works
for LangChain4j applications unchanged.

## Decisions taken with the owner (2026-11-20)

| Question | Decision |
|---|---|
| How it plugs into LangChain4j | **A decorator around the application's `ContentRetriever`** that records into neocortex's `RetrievalTracker` — the shape of neocortex's `TrackingCaseRetriever` and of this repo's CRAG module. Rejected: a listener (`ContentRetriever` has no hooks); wrapping neocortex's `CaseRetriever` (would track hybrid-search only, not pgvector, web search or the application's other retrievers). |
| Document identity | **Segment metadata `source_document_id`** — the key this repo already uses (hybrid-search writes it, CRAG reads it, the consumer guide documents it). |
| Behaviour details | The behaviour listed under *Design* was approved as proposed. |

## Goals

1. Every retrieval of a wrapped retriever is recorded with query, tenant, corpus and the returned
   documents with their scores.
2. Each returned content carries the retrieval ID, so the application can send feedback for it.
3. Each recorded retrieval is announced as an event.
4. Tracking never changes what the retriever returns and never fails a retrieval.
5. A retrieval is recorded once, even when another tracking decorator already recorded it.
6. Works in Quarkus and Spring Boot and composes with the tenancy and CRAG modules. The application
   adds this module plus a tracker: on Quarkus neocortex's `casehub-neocortex-rag-tracking` and its
   `casehub.rag.tracking.sqlite.path`, on Spring a `RetrievalTracker` bean — the same pattern as
   CRAG, where the application adds the evaluator.

## Non-goals

- Analysis code of our own. `RetrievalAnalyzer` (neocortex, static methods over a
  `RetrievalTracker`) is used directly; the consumer guide shows how.
- Storage of our own. The tracker implementation (neocortex's `SqliteRetrievalTracker`,
  `InMemoryRetrievalTracker`, or the application's) owns storage, retention and schema.
- Automatic feedback (inferring usefulness from the model's answer).
- Reactive retrievers.

## Anti-goals

- A tracking failure (tracker down, bad data) changes, delays visibly or breaks a retrieval.
- One tenant's retrievals recorded under another tenant.
- Invented document IDs in the record (they would corrupt the analysis: one document counted as
  many, never-retrieved detection meaningless).
- A retrieval recorded twice.

## Constraints

- LangChain4j `1.19.0`; `casehub-neocortex-rag-api` for `RetrievalTracker`, `RetrievalQuery`,
  `CorpusRef`, `RetrievedChunk`, `RetrievedDocumentRef`, `RetrievalRecorded`.
- Repo rules: `-core` pure Java; Quarkus modules inject (never look up) the beans they use, so
  `casehub-neocortex-rag-api` and `casehub-platform-api` (`CurrentPrincipal`) are required
  dependencies of the Quarkus module; Spring follows `crag-spring` (`@ConditionalOnClass`,
  `ObjectProvider`).
- **The tracker is not a dependency of this module** *(builder-check finding, decided by the CRAG
  precedent and the repo's graceful-degradation rule)*: `SqliteRetrievalTracker` has a required
  `casehub.rag.tracking.sqlite.path` with no default, so shipping `rag-tracking` transitively would
  make startup fail for anyone who has not configured it. The application adds `rag-tracking` (or
  another tracker); without one the module degrades to "not wrapped, one INFO".
- `SqliteRetrievalTracker` (neocortex `rag-tracking`) is a CDI bean configured with
  `@ConfigProperty` fields and `@PostConstruct`; it cannot be built outside CDI. Its SQLite schema
  declares `relevance_score REAL NOT NULL` (a NaN score is stored as NULL and fails the insert).
- `CorpusRef` and `RetrievalQuery` reject null/blank tenant, corpus and query text.

## Design

### Modules

`tracking-core` (pure Java), `tracking` (Quarkus), `tracking-spring` (Spring Boot); package
`io.casehub.langchain4j.tracking`; artifacts `casehub-langchain4j-tracking-core`,
`casehub-langchain4j-tracking`, `casehub-langchain4j-tracking-spring`; BOM entries for all three.

### `TrackingContentRetriever` (tracking-core)

```java
public TrackingContentRetriever(ContentRetriever delegate, RetrievalTracker tracker, String corpusName,
        Supplier<String> tenantId, Consumer<RetrievalRecorded> recorded)
```

- `tenantId` returns the tenant to record under, or `null` when it cannot be determined (the
  retrieval is then not recorded). The wiring supplies it (below).
- Public constant `RETRIEVAL_ID_KEY = "retrieval_id"`; `delegate()` accessor (as in
  `CragContentRetriever`).

`retrieve(Query query)`:

1. `contents = delegate.retrieve(query)` — delegate exceptions propagate unchanged (they are the
   retriever's, not tracking's).
2. **Already recorded upstream** — if **any** content's segment metadata has `retrieval_id` or
   neocortex's `_trackingId` (neocortex's own `anyMatch` rule), do not record. Return the contents,
   each one carrying `_trackingId` but no `retrieval_id` getting `retrieval_id` = its `_trackingId`,
   so the application reads one key. (`_trackingId` appears when hybrid-search's `CaseRetriever` is
   tracked by neocortex — `casehub.rag.tracking.enabled=true` — because
   `CasehubHybridContentRetriever` copies chunk metadata into the segment.)
3. **Skip recording** (return `contents` as is) when `query.text()` is null or blank, or
   `tenantId.get()` returns null or blank.
4. **An empty `contents` is recorded** with an empty chunk list — neocortex records empty results
   too, and `RetrievalAnalyzer.zeroHitQueries` counts exactly those records *(builder-check
   correction)*.
5. Map each content that has a text segment **and** a non-blank `source_document_id` to a
   `RetrievedChunk`: content = segment text; `sourceDocumentId` = `source_document_id`;
   `relevanceScore` = `ContentMetadata.RERANKED_SCORE` if a finite number, else
   `ContentMetadata.SCORE` if a finite number, else `0.0`; metadata = segment metadata as strings.
   Contents without `source_document_id` are not recorded; the first time a retriever returns one,
   one WARN names the missing key. If `contents` is **non-empty but no content maps**, do not record
   *(my call)* — recording an empty list would appear as a false zero-hit.
6. `retrievalId = tracker.record(RetrievalQuery.of(query.text()), new CorpusRef(tenant, corpusName),
   chunks, contents.size())` — `maxResults` is the number of contents returned, because
   `ContentRetriever` has no result-count parameter.
7. Publish `new RetrievalRecorded(retrievalId, query, corpus, documentRefs)` to `recorded`
   (`documentRefs`: one per `sourceDocumentId`, max score — neocortex's collapse rule).
8. Return **all** contents (recorded or not), in the delegate's order, each with a text segment
   gaining segment metadata `retrieval_id = retrievalId`. Content metadata (scores) is kept.

**Failure isolation:** any exception from steps 2–8 (tracker, event consumer, mapping) is logged at
WARN and the delegate's original `contents` are returned unchanged — the neocortex contract
("tracking is purely observational"). If `record` succeeded and only the event consumer threw, the
stamped contents are still returned *(my call: the record exists, so the ID is valid for feedback)*.

### Tenant

- **Quarkus and Spring:** with a `CurrentPrincipal` bean, `tenantId` = `principal.tenancyId()`,
  read per retrieval; a principal without a tenancy ID yields `null` (not recorded, one WARN) —
  never a fallback that would mix tenants.
- Without a `CurrentPrincipal` bean (a single-tenant application), `tenantId` = `"default"`; one
  INFO at startup says retrievals are recorded under tenant `default`.

### Corpus

Property `casehub.langchain4j.tracking.corpus`, default `default`, same in both frameworks. One
corpus name for every wrapped retriever *(known limit, below)*.

### Activation and composition

Order: **Tracking(CRAG(Tenancy(app)))** — tracking records what the model actually receives, after
the tenant filter and the CRAG filter, as neocortex orders its own decorators (tracking outermost).

- **Quarkus (`tracking`)** — a producer `@Produces @Alternative @Priority(300) @ApplicationScoped
  ContentRetriever` (`TrackingBeans.PRIORITY = 300`) that wraps the bean the application would
  otherwise get, found as `CragBeans` does (`BeanManager.getBeans`, excluding its own producer,
  ignoring alternatives that outrank it, ArC's resolution rules; copy of `narrow`, kept in step).
  With CRAG and/or tenancy present, their alternative is that bean. Injected: `@Any
  Instance<ContentRetriever>` keep-alive, `Instance<RetrievalTracker>`, `Instance<CurrentPrincipal>`,
  `Event<RetrievalRecorded>`, the corpus property.
  - No `RetrievalTracker` bean → the resolved retriever is returned unwrapped; one INFO.
  - Several `RetrievalTracker` beans → a clear `AmbiguousResolutionException`, as CRAG does for
    evaluators. (An `@Alternative` tracker such as neocortex's `InMemoryRetrievalTracker`,
    `@Priority(1)`, beside `SqliteRetrievalTracker` is not ambiguous — ArC picks the alternative.)
  - **Required change in `tenancy` and `crag`:** both warn when an alternative with a higher
    priority outranks them ("…is injected without it — keep application alternatives below 100").
    The tracking producer (priority 300) would trigger that warning falsely in every application
    that has tracking. The warning must skip this library's own decorating producers (bean class in
    package `io.casehub.langchain4j` whose simple name ends in `Beans`); their selection rule
    (ignore outranking alternatives) is unchanged. Regression tests: no warning with the tracking
    producer present; still a warning for an application alternative.
- **Spring (`tracking-spring`)** — a `BeanPostProcessor` that wraps every `ContentRetriever` bean
  that is not already a `TrackingContentRetriever`, implementing `Ordered` with
  `TrackingAutoConfiguration.POST_PROCESSOR_ORDER = Ordered.LOWEST_PRECEDENCE - 50` — after CRAG
  (`LOWEST_PRECEDENCE - 100`) and tenancy (`HIGHEST_PRECEDENCE + 100`). The tracker is read through
  `ObjectProvider<RetrievalTracker>`; several beans → a clear `IllegalStateException` (as CRAG).
  Events go through `ApplicationEventPublisher`. No tracker bean → not wrapped; one INFO.
- **Events** — neocortex's `RetrievalRecorded` is fired as is *(my call)*: unlike CRAG's
  `RetrievalQuality`, a record from either pipeline lives in the same tracker and takes feedback the
  same way, so an observer has no reason to tell the sources apart. Quarkus fires synchronously.
- **The hybrid-search bean is not wrapped** *(my call, builder-check finding)*: it searches the
  corpora named in its own configuration, and recording it here under
  `casehub.langchain4j.tracking.corpus` would split one corpus's documents across two names in the
  analysis. In Quarkus its retrievals can be tracked by neocortex itself
  (`casehub.rag.tracking.enabled=true`), under the right corpora; in Spring they are not tracked
  (neocortex's tracking is a CDI decorator — #20) *(final-review correction)*. Spring's post-processor skips the `casehubHybridRetriever` bean; Quarkus
  wraps only the unqualified retriever, which the qualified hybrid bean never is.

### Spring tracker

`SqliteRetrievalTracker` cannot be created by Spring. A Spring application declares a
`RetrievalTracker` bean itself (neocortex's `InMemoryRetrievalTracker` for tests, or its own
implementation). **TODO** (#20): a Spring-friendly SQLite tracker — upstream in
neocortex (a plain constructor on `SqliteRetrievalTracker`) or a Spring adapter here.

### Feedback and analytics (consumer guide only)

```java
List<Content> contents = retriever.retrieve(query);
String retrievalId = contents.get(0).textSegment().metadata().getString("retrieval_id");
tracker.feedback(retrievalId, "doc-42", RetrievalOutcome.HIGHLY_RELEVANT);

Map<String, DocumentStats> stats = RetrievalAnalyzer.documentStats(tracker, corpus, since, until);
```

The guide states: the tracker dependency and its path property (Quarkus) or bean (Spring); ID key `source_document_id` must be set at ingestion (`Metadata` on the
`Document`/`TextSegment`); contents without it are returned but not recorded; the corpus name and
tenant used in `CorpusRef` for analysis.

## Known limits (documented, not solved)

- One corpus name for every wrapped retriever in an application.
- `maxResults` is the returned count, not the requested limit.
- A missing score is recorded as `0.0` (the SQLite schema rejects NaN).
- A non-empty retrieval in which no content has `source_document_id` is not recorded.
- Hybrid-search retrievals are tracked only through neocortex's own tracking (Quarkus only).
- `retrieval_id` in segment metadata makes the same passage from two retrievals unequal, so a
  multi-retrieval pipeline (query expansion, routing) no longer de-duplicates it *(final review)*.
- Spring needs an application-provided tracker (TODO above).

## Tests the builder must include

- Recording: query text, tenant, corpus, chunks with `source_document_id` and score precedence
  (RERANKED_SCORE → SCORE → 0.0, non-finite skipped); `maxResults` = returned count.
- All contents returned in order with `retrieval_id`, including those without `source_document_id`
  (returned, stamped, not recorded); one WARN for the missing key, not one per retrieval.
- Empty list recorded with no chunks (appears in `zeroHitQueries`).
- Skips: any content already stamped (`retrieval_id` / `_trackingId`; `_trackingId` copied to
  `retrieval_id`; a mixed list not recorded), blank query, null tenant, non-empty with no mappable
  content — tracker not called.
- Failure isolation: tracker throws → original contents, no event; event consumer throws → stamped
  contents returned.
- Delegate exception propagates.
- Quarkus: Tracking(CRAG(Tenancy(app))) with all three modules — the tracker sees only the current
  tenant's, CRAG-filtered content; first retrieval terminates; no tracker bean → unwrapped; several
  trackers → clear error; no `CurrentPrincipal` → tenant `default`; tenancy/CRAG emit no outranking
  warning for the tracking producer.
- Spring: same nesting asserted (outer `TrackingContentRetriever` → `CragContentRetriever` →
  `TenantIsolatingContentRetriever`); already-tracking bean and `casehubHybridRetriever` not wrapped;
  no tracker → unwrapped; event published.

## Calls to confirm

1. The tracker (neocortex `rag-tracking`) is added by the application, not brought by this module.
2. A non-empty retrieval with no `source_document_id` anywhere is not recorded.
3. The hybrid-search bean is left to neocortex's own tracking, not wrapped here.
4. Contents stay stamped when only the event consumer failed.
5. neocortex's `RetrievalRecorded` is fired directly, not wrapped like `CragRetrievalQuality`.

## Left to the builder

Class names other than `TrackingContentRetriever`, `TrackingBeans`, `TrackingAutoConfiguration`;
log wording; test layout; whether the missing-key WARN is per retriever instance or per JVM.
