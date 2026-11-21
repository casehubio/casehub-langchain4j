# CRAG quality filter — design

**Date:** 2026-11-20 · **Chapter:** ARC42 C8 (Phase 1 extended) · **Branch:** `feat/crag-filter` (from `feat/phase-1-core`)

## Intent

A retriever that works can still return the wrong things — vocabulary mismatch, a stale corpus, an
ambiguous query. Those chunks reach the LLM as context and degrade its answer; in regulated domains
(AML, clinical) that means entity confusion and wrong risk or compliance conclusions. LangChain4j
passes whatever a `ContentRetriever` returns straight to the model.

neocortex already solves this for its own `CaseRetriever` pipeline with Corrective RAG
(`docs/specs/2026-06-18-corrective-rag-crag-design.md` in neocortex): a `RelevanceEvaluator` grades
each chunk CORRECT / AMBIGUOUS / INCORRECT, INCORRECT chunks are dropped, CORRECT ones are put
first. This module brings the same gate to **any** LangChain4j `ContentRetriever` — pgvector,
Qdrant used directly, web search, SQL — as ARC42 C8 describes.

## Decision taken with the owner (2026-11-20)

| Question | Decision |
|---|---|
| How it plugs into LangChain4j | **A decorator around the application's `ContentRetriever`**, grading with neocortex's `RelevanceEvaluator` SPI. Rejected: a `ScoringModel` adapter for LangChain4j's `ReRankingContentAggregator` (no three-way grade, no quality signal, no evaluator choice); a custom `ContentAggregator` (would replace the application's own aggregator). |

## Goals

1. Every content list a wrapped retriever returns is graded before the model sees it.
2. INCORRECT content is removed; CORRECT content comes before AMBIGUOUS, original order kept within
   each grade.
3. Each surviving content carries its grade (and the evaluator's score when it has one).
4. Content already graded upstream is not graded again.
5. Each retrieval publishes a quality summary (how many retrieved / correct / ambiguous / incorrect).
6. Works in Quarkus and Spring Boot, composes with the tenancy module, adding the module is the only
   step.

## Non-goals

- **Search expansion.** neocortex re-queries with a larger limit when too few chunks survive.
  `ContentRetriever.retrieve(Query)` has no result-count parameter, so a decorator cannot ask an
  arbitrary retriever for more. `RetrievalQuality.expandedSearch` is always `false`.
- Choosing or shipping an evaluator model, or thresholds. The `RelevanceEvaluator` bean (neocortex's
  cross-encoder or ColBERT evaluator, or the application's own) owns grading and calibration.
- Query expansion (HyDE etc.) — a pre-retrieval concern.

## Anti-goals

- INCORRECT content reaches the model.
- Content from another tenant is sent to the evaluator.
- Adding the module breaks an application that has no evaluator (it must degrade, not fail).
- Content graded upstream (for example hybrid-search results already passed through neocortex CRAG)
  is graded a second time.

## Constraints

- LangChain4j `1.19.0`; `casehub-neocortex-rag-api` for `RelevanceEvaluator`, `RetrievedChunk`,
  `RelevanceGrade`, `ScoredGrade`, `RetrievalQuality`.
- Repo rules: `-core` pure Java; Quarkus modules inject (never look up) the beans they use, so
  `casehub-neocortex-rag-api` is a required dependency of the Quarkus module; Spring keeps
  `ClassUtils` checks and holders.
- The hybrid-search module already writes `relevance_grade` into segment metadata; the same key is
  used here.

## Design

### Modules

`crag-core` (pure Java), `crag` (Quarkus), `crag-spring` (Spring Boot); package
`io.casehub.langchain4j.crag`; BOM entries for all three.

### `CragContentRetriever` (crag-core)

```java
public CragContentRetriever(ContentRetriever delegate, RelevanceEvaluator evaluator, Consumer<RetrievalQuality> quality)
```

`retrieve(Query query)`:

1. `contents = delegate.retrieve(query)`; empty → return it (no quality event).
2. If every content's segment metadata has `relevance_grade` other than `UNGRADED` → already graded:
   return as is (no evaluation, no event).
3. Map each content to a `RetrievedChunk`: content = segment text; `sourceDocumentId` = metadata
   `source_document_id` if present, else `"content-" + index`; `relevanceScore` =
   `ContentMetadata.SCORE` if numeric, else `0.0`; metadata = segment metadata as strings.
4. `scored = evaluator.evaluateChunks(query.text(), chunks)` — one call per retrieval.
5. Drop INCORRECT; order CORRECT, then AMBIGUOUS, each in original order.
6. Each surviving content keeps its original segment and metadata, plus `relevance_grade` = grade
   name, and — when the score is a real number — `ContentMetadata.RERANKED_SCORE` = score.
7. Publish `RetrievalQuality(total, correct, ambiguous, incorrect, evaluated = true, expandedSearch = false)`
   to `quality`; a throwing consumer is logged, never propagated.
8. Return the survivors — possibly an empty list. An empty list is the correct answer when nothing
   is relevant.

Evaluator contract violations — a result list of the wrong size, a null entry, or an `UNGRADED`
grade — and any evaluator exception are treated as an evaluator failure (below).

### When the evaluator fails *(my call — see "Calls to confirm")*

The retrieval is returned unfiltered, every content marked `relevance_grade = UNGRADED`, a WARN is
logged, and `RetrievalQuality(evaluated = false)` is published. Rationale: an evaluator outage is an
infrastructure fault; dropping all context turns it into a silent RAG outage. The `UNGRADED` mark
lets a downstream prompt or audit see the content was not checked.

### Activation and composition

- **Quarkus (`crag`)** — a producer `@Produces @Alternative @Priority(200) @ApplicationScoped
  ContentRetriever` that wraps the bean the application would otherwise get. It finds that bean the
  way the tenancy module does (`BeanManager.getBeans`, excluding its own producer, ArC's resolution
  rules) — and because tenancy's alternative has priority 100, the tenancy wrapper *is* that bean
  when both modules are present. Order: CRAG(Tenancy(app)) — the tenant filter runs first, so other
  tenants' content never reaches the evaluator. The `@Any Instance<ContentRetriever>` keep-alive and
  `Instance<RelevanceEvaluator>` are injected (`Instance`, so a missing evaluator degrades instead of
  failing deployment).
  - **Required change in `tenancy` *(builder-check finding)*:** tenancy's selection excludes only its
    own producer, so with CRAG present it would pick the higher-priority CRAG alternative as its
    delegate — CRAG → Tenancy → CRAG → … on the first retrieval. Tenancy's selection must ignore
    every decorating producer of this library, not only its own: it skips beans whose bean class is
    any `*Beans` producer of `io.casehub.langchain4j` that wraps `ContentRetriever` — concretely, an
    alternative with a priority above its own (100) is never a delegate candidate. Add a regression
    test in `tenancy` with a higher-priority decorating alternative present.
- **Spring (`crag-spring`)** — a `BeanPostProcessor` that wraps every `ContentRetriever` bean that is
  not already a `CragContentRetriever`. It must run **after** tenancy's post-processor so the result
  is CRAG(Tenancy(app)). *(Builder-check finding: Spring runs `PriorityOrdered`, then `Ordered`, then
  unordered post-processors — tenancy's is unordered today, so an `Ordered` CRAG processor would run
  first and tenancy would wrap CRAG.)* **Required change in `tenancy-spring`:** its post-processor
  implements `Ordered` with order `Ordered.HIGHEST_PRECEDENCE + 100`; the CRAG one implements
  `Ordered` with `Ordered.LOWEST_PRECEDENCE - 100`. A test in `crag-spring` with both modules asserts
  the nesting (the outer bean is a `CragContentRetriever` whose delegate is a
  `TenantIsolatingContentRetriever`).
- **No `RelevanceEvaluator` bean** — retrievers are not wrapped; one WARN at startup naming the
  missing evaluator. Never a startup failure.
- **Quality events** — *(my call, from the builder check)* neocortex's own CRAG already fires an
  unqualified `RetrievalQuality` CDI event, and the record carries no source, so this module wraps
  it: `record CragRetrievalQuality(RetrievalQuality quality)` in `crag-core`. Quarkus fires
  `Event<CragRetrievalQuality>` synchronously; Spring publishes it as an application event.
  `crag-core` only knows the `Consumer<RetrievalQuality>`.
- The hybrid-search bean (`casehubHybridRetriever` / `@HybridSearch`) is opt-in and qualified, so the
  unqualified producer/post-processor rules above treat it like any other `ContentRetriever` bean:
  in Spring it is wrapped (its results are skipped by step 2 when neocortex CRAG already graded
  them); in Quarkus only the unqualified retriever is wrapped.

### Tests the builder must include

- Grade mapping, filtering and ordering, including all-INCORRECT → empty list.
- Already-graded input skipped (no evaluator call).
- Evaluator throws / wrong-size result / UNGRADED grade → unfiltered, `UNGRADED`, `evaluated=false`.
- Quality consumer throws → retrieval still returned.
- Score attached only when finite.
- Quarkus: with tenancy present, the evaluator sees only the current tenant's content (CRAG wraps
  the tenancy wrapper) and the first retrieval terminates (no mutual wrapping); without an evaluator
  bean the app's retriever is returned unwrapped.
- Spring: same two cases (nesting asserted as CRAG(Tenancy(app))), plus "already a
  `CragContentRetriever`" is not re-wrapped.
- Score attached only when `Float.isFinite(score)` (`ScoredGrade.score` is a primitive `float`).

## Calls to confirm

1. **Evaluator failure passes content through unfiltered** (marked `UNGRADED`) rather than dropping it.
2. **Wrap automatically** every unqualified `ContentRetriever` when the module and an evaluator are
   present (the repo's classpath-activation rule), rather than an opt-in per retriever.

## Left to the builder

Class names other than `CragContentRetriever`, log wording, test layout, how the Quarkus producer
shares the bean-selection code with the tenancy module (copy or extract).
