# Retrieval tracking at the augmentor — design

**Date:** 2026-11-23 · **Issue:** #28 · **Chapter:** ARC42 C9 (follow-up) · **Branch:** `feat/augmentor-tracking` (from `main`)

## Intent

Retrieval tracking (C9, `docs/superpowers/specs/2026-11-20-retrieval-tracking-design.md`) records
each retrieval of a wrapped `ContentRetriever` into neocortex's `RetrievalTracker`, so neocortex's
`RetrievalAnalyzer` works on LangChain4j retrievals.

That breaks as soon as the application's `RetrievalAugmentor` rewrites the question with a
`QueryTransformer` — `ExpandingQueryTransformer` (several rephrasings, the original dropped),
`CompressingQueryTransformer` (a standalone question from the chat history), or the application's
own (HyDE, step-back). `DefaultRetrievalAugmentor` calls the retrievers once per rewritten query, so
the retriever decorator records **N retrievals per question, each under LLM-generated text, and the
user's question nowhere**. Reproduced on 2026-11-23 with a two-variant transformer: records
`[variant one, variant two]`, no record of the question. `RetrievalAnalyzer` then counts one
question N times, under texts nobody asked, and feedback through `retrieval_id` reaches only one of
the N records.

neocortex itself does not have this problem: its tracking decorator (`@Priority(40)`) sits
*outside* its query expansion (`@Priority(200)`), so it records one retrieval per question, under
the original text, with the fused result. This design gives LangChain4j applications the same:
**one record per question, at the augmentor.**

## Decisions taken with the owner (2026-11-23)

| Question | Decision |
|---|---|
| How to fix | **A decorator around the application's `RetrievalAugmentor`** that records once per question — the user's text and the contents after aggregation. Rejected: recording the user's text at the retriever (still N records per question); documenting it as a limit. |
| C15 (query expansion module) | Not built for now — LangChain4j already has `QueryTransformer`/`ExpandingQueryTransformer`; neocortex's expanders are tied to the platform `AgentProvider`. Raised with the roadmap owner separately. Out of scope here. |

## Goals

1. With an augmentor that rewrites the question, each question is recorded **once**: the user's
   message text, the final contents (after aggregation), the tenant and corpus — as the retriever
   decorator records today.
2. Retrievers wrapped by `TrackingContentRetriever` inside that augmentor do **not** record.
3. The final contents carry `retrieval_id`, so the application gets it from the AI service's
   sources (`Result.sources()`) and can send feedback.
4. One `RetrievalRecorded` event per question.
5. Works in Quarkus and Spring Boot with no extra configuration: a `RetrievalAugmentor` bean is
   wrapped the way `ContentRetriever` beans are wrapped today.
6. Applications without a `RetrievalAugmentor` bean (an AI service with a `ContentRetriever` only)
   behave exactly as today.

## Non-goals

- Changing CRAG. CRAG grades each retrieval against the query that retrieved it; for a compressing
  transformer that query is *better* than the raw message ("how much is it?" vs "how much is the
  iPhone 15?"). Documented, not changed.
- Recording the rewritten queries (`expandedText`). The record holds the question; the variants are
  an implementation detail of the augmentor. (Possible later: neocortex's `RetrievalQuery` has an
  `expandedText` slot.)
- Tracking hybrid-search through the augmentor: hybrid results carry neocortex's `_trackingId` and
  stay tracked by neocortex itself (existing "already recorded upstream" rule).
- Augmentors that are not beans: built inline (Spring, plain LangChain4j) or a Quarkus supplier
  class without a scope. They can be wrapped by hand; the consumer guide shows how.

## Anti-goals

- A tracking failure changes, fails or visibly delays the augmentation.
- A question recorded twice (augmentor **and** retriever), or not at all because the two decorators
  each think the other records.
- One tenant's retrievals recorded under another tenant.
- The marker this design uses leaking into what the model receives or into later invocations.

## Constraints

- LangChain4j `1.19.0`; `RetrievalAugmentor.augment(AugmentationRequest) → AugmentationResult`
  (`chatMessage`, `contents`). `DefaultRetrievalAugmentor` builds every query from
  `augmentationRequest.metadata()`, and `ExpandingQueryTransformer` / `CompressingQueryTransformer`
  copy that metadata into the queries they create.
- Multi-query retrievals run on the augmentor's executor threads, so a `ThreadLocal` cannot carry
  "an augmentor is tracking this" to the retrievers.
- Same recording rules as `TrackingContentRetriever` (tenant, `source_document_id`, scores, empty
  results, upstream IDs) — one implementation, not two.

## Approaches considered

| Approach | Trade-off | Outcome |
|---|---|---|
| Decorate `RetrievalAugmentor`, retrievers stand down via a marker in the query metadata | One record per question, matches neocortex; needs a marker the retriever can see on executor threads | **Chosen** |
| Retriever records the user's text (`metadata().chatMessage()`) | Tiny change; still N records per question | Rejected |
| Document as a known limit | No code; analytics stay wrong for every rewriting augmentor | Rejected |

## Design

### `TrackingRetrievalAugmentor` (tracking-core)

`public class TrackingRetrievalAugmentor implements RetrievalAugmentor`, constructor
`(RetrievalAugmentor delegate, RetrievalTracker tracker, String corpusName, Supplier<String> tenantId, Consumer<RetrievalRecorded> recorded)`
— the same parameters as `TrackingContentRetriever`; `delegate()` accessor.

`augment(request)`:

1. **Mark** the request: the marker is the entry `TRACKED_BY_AUGMENTOR_KEY` in the request
   metadata's `InvocationParameters`. (`Metadata` always has an `InvocationContext` in 1.19.0 —
   its constructor rejects null — but the context's `invocationParameters()` may be null, as
   `Metadata.from(message, memoryId, memory)` produces.)
   - `invocationParameters()` is non-null → mark that object (the same one every query will
     carry; plain AI services always supply one, the caller's or a fresh one). The marker value is
     a counter: increment on entry, decrement in `finally`, remove the entry at zero — all under
     `synchronized` on that `InvocationParameters`. A caller that shares one
     `InvocationParameters` across concurrent calls therefore never sees the marker vanish while
     another call is in flight *(my call — see review note)*.
   - `invocationParameters()` is null → pass the delegate a copy of the request whose metadata
     carries a copy of the `InvocationContext` (every builder field copied: invocation ID,
     interface and method name, arguments, user message, memory ID, default request parameters,
     model provider, managed parameters, timestamp) with fresh
     `InvocationParameters` holding the marker; system message and chat memory copied too.
2. `result = delegate.augment(markedRequest)`.
3. Record once, with the shared recording logic (below): query text = the request's user message
   text (`UserMessage.singleText()`; a request that is not a `UserMessage` is passed through
   unrecorded), contents = `result.contents()`, `maxResults` = their count.
4. Return an `AugmentationResult` with the same `chatMessage` and the **stamped** contents. The
   injected message is not touched (it was built inside the delegate; the default content injector
   does not show metadata keys to the model).

Any tracking failure → WARN, and the delegate's result is returned unchanged. A failure inside the
delegate propagates as before (the marker is removed first).

### `TrackingContentRetriever` stands down

At the start of `retrieve`: if `query.metadata()` (may be null — `Query.from(text)`) →
`invocationContext()` → `invocationParameters()` (may be null) contains `TRACKED_BY_AUGMENTOR_KEY`, return the delegate's contents untouched — no record, no stamp, no
`_trackingId` copy (the augmentor applies the upstream rule once, to the final contents).

A transformer that builds queries **without** the original metadata loses the marker; those
retrievals are recorded at the retriever as today (documented).

### Shared recording logic

The body of `TrackingContentRetriever.track(...)` (skip rules, chunk mapping, scores, record,
stamp, event, upstream-ID copy) moves to a package-private `RetrievalRecorder` used by both
decorators, so the two cannot drift. Behaviour of `TrackingContentRetriever` is unchanged apart
from the stand-down.

**Partly recorded upstream** *(my call — see review note)*. At the retriever a list with any
upstream ID is not recorded (all or nothing) — unchanged. At the augmentor the final list can mix a
router's hybrid-search results (recorded by neocortex, `_trackingId`) with a plain retriever's
results recorded by nobody; all-or-nothing would lose the latter. So the augmentor records **only
the contents without an upstream ID** (one record, the question's text), stamps those with the new
ID, and copies `_trackingId` to `retrieval_id` on the others. All contents already recorded → no
record, IDs copied (as today). The recorder takes this as a parameter.

### Framework wiring

- **Spring** (`TrackingAutoConfiguration`): the existing post-processor also wraps beans that are
  `RetrievalAugmentor` (not already a `TrackingRetrievalAugmentor`), with the same tracker, tenant,
  corpus and event publisher; no tracker → left alone, as for retrievers.
- **Quarkus** (`TrackingBeans`) — automatic, like Spring (D4: both frameworks; "adding the
  module is the only step"), with no deployment module. quarkus-langchain4j finds an AI service's
  augmentor in one of three ways; the first two are covered automatically:
  1. **A `RetrievalAugmentor` bean** (the default `BeanIfExistsRetrievalAugmentorSupplier`: the
     recorder asks `Instance<RetrievalAugmentor>.isResolvable()`, then `get()`). Covered by an
     `@Alternative @Priority(300)` producer for `RetrievalAugmentor` that is **`@Dependent`** and
     resolves the bean ArC would otherwise inject with the existing `narrow` rules. When the
     application has no `RetrievalAugmentor` bean the producer returns **`null`** — legal for a
     `@Dependent` producer — and the AI service is built with no augmentor, exactly as without
     this module (`AiServices.retrievalAugmentor(null)` only stores the null). `@Dependent`
     because a normal-scoped bean cannot be null and the producer bean exists whether or not the
     application has an augmentor.
  2. **A `Supplier<RetrievalAugmentor>` bean** named in `@RegisterAiService(retrievalAugmentor =
     …)` — quarkus-langchain4j's documented pattern (`@ApplicationScoped class Augmentor implements
     Supplier<RetrievalAugmentor>`); the recorder takes the bean via `getInjectedReference`. Covered
     by a CDI `@Decorator` on `Supplier<RetrievalAugmentor>` whose `get()` wraps the delegate's
     result (not re-wrapping a `TrackingRetrievalAugmentor`).
  3. **A supplier class without a scope** — instantiated reflectively, outside CDI; cannot be
     intercepted. Documented: wrap by hand with `TrackingRetrievalAugmentor`.

  Both mechanisms (null from the `@Dependent` producer accepted end to end; decorator applied to a
  `Supplier<RetrievalAugmentor>` bean the recorder looks up) are **verified by `@QuarkusTest`
  first**, before the rest of the Quarkus work; if either fails, stop and come back to the owner.

### Composition

Tracking(augmentor) → [transformer → router → Tracking(CRAG(Tenancy(retriever))) stands down → …]
→ aggregator (optionally `RetrievalIdAwareContentAggregator`, harmless: nothing is stamped before
aggregation now). The record holds what the model receives.

## Tests the builder must include

- Rewriting augmentor (transformer returning two variants, metadata copied) over a wrapped
  retriever: exactly **one** record, query text = the user's message, chunks = aggregated contents;
  every returned content stamped with that record's ID; one event.
- Same without a transformer (single query): one record (not two).
- Request whose `InvocationContext` has null `invocationParameters` (`Metadata.from(...)`) → still
  one record; the delegate saw the marker; other context fields preserved in its copy.
- Request with caller `InvocationParameters` → marker present during the call, removed after;
  caller's other entries untouched.
- Two concurrent calls sharing one `InvocationParameters` (one finishing first) → the other's
  retrievers still stand down; exactly one record per call.
- Query without metadata (`Query.from(text)`) at a wrapped retriever → recorded as today, no NPE.
- Augmentor result mixing a `_trackingId` content with untracked ones → one record holding only
  the untracked ones; the tracked one gets its upstream ID as `retrieval_id`.
- Transformer that drops metadata → retriever records as today (documents the limit).
- Tracker throws → result returned unchanged, WARN; delegate throws → exception propagates, marker
  removed.
- Non-`UserMessage` request → passed through, no record.
- Upstream IDs on final contents (`_trackingId`) → not recorded, copied to `retrieval_id`.
- `TrackingContentRetriever` behaviour unchanged when no marker (existing tests stay green).
- Spring: a `RetrievalAugmentor` bean is wrapped; no tracker → not wrapped; an AI-service-style
  flow records once.
- Quarkus: an application `RetrievalAugmentor` bean resolves to the tracking one; an application
  with only a `ContentRetriever` gets a null augmentor and works as before; a
  `Supplier<RetrievalAugmentor>` bean's `get()` returns a tracking augmentor; each through an AI
  service (`@RegisterAiService`) records once per question.

## Docs

Consumer guide "tracking" section: rewriting augmentors, what is recorded, the metadata caveat,
CRAG's behaviour with transformers; wrapping a non-bean augmentor by hand. ARC42 C9 key classes.
Retrieval tracking spec: note superseding the N-records behaviour.

## Revision 2026-11-24 — Quarkus wiring as an extension (owner's decision)

The final review proved the `@Dependent` null-returning producer wrong:
`AiServices.retrievalAugmentor(null)` calls `ensureNotNull` (LangChain4j 1.19.0, 1.20.2), so with
quarkus-langchain4j's default lookup every AI service of an application **without** an augmentor
would fail to build. The copied `narrow` fallback could also hand a qualified-only augmentor to
default AI services, or fail where ArC reports ambiguity. Asked, the owner chose a **Quarkus
extension**, which supersedes the *Quarkus* bullet of *Framework wiring*:

- `tracking` becomes the extension's **runtime** module (extension descriptor via
  `quarkus-extension-maven-plugin`, `META-INF/quarkus-extension.yaml`); a new
  **`tracking-deployment`** module (`casehub-langchain4j-tracking-deployment`, BOM entry) holds
  `TrackingProcessor` — the layout casehub-ledger and casehub-work use. Tests stay in the runtime
  module as `@QuarkusTest` (ledger does the same).
- **Build step** (`BeanDiscoveryFinishedBuildItem` → `SyntheticBeanBuildItem`, the pattern
  quarkus-langchain4j and its EasyRAG use): resolve `RetrievalAugmentor` with `@Default` through
  ArC's own `BeanResolver` (`resolveBeans`, then `resolveAmbiguity`). Exactly one → register a
  synthetic `RetrievalAugmentor` bean, `@Alternative @Priority(300)`, `@Singleton`, implementation
  class `TrackingRetrievalAugmentor`, whose runtime creator returns
  `TrackingBeans.trackedApplicationAugmentor()` (the bean ArC would otherwise inject, wrapped;
  unwrapped when there is no tracker). None or ambiguous → register **nothing**: quarkus-langchain4j
  sees exactly what it saw without this module.
- **EasyRAG**: it registers its own synthetic augmentor from the same build item when no other
  exists, so the step above cannot see it. When EasyRAG is on the classpath
  (`io.quarkiverse.langchain4j.easyrag.runtime.EasyRetrievalAugmentor`) and no application
  augmentor resolves, the step registers the tracking bean too; at runtime it wraps EasyRAG's.
  Other extensions' synthetic augmentors are not covered (documented).
- The `@Dependent` producer is removed. `TrackingAugmentorSupplierDecorator` stays.
- Tests: no augmentor → `Instance<RetrievalAugmentor>` **unresolvable** (the real lookup then
  gives the AI service none); one `@Default` augmentor (class bean and producer bean) → tracking;
  a qualified-only augmentor → unresolvable; two `@Default` augmentors → unresolvable, app starts;
  no tracker → the app's augmentor injected unwrapped.

Also from the final review, fixed with this revision: **record only when a retrieval ran** — the
marker counts retriever calls that stood down; an augmentation that called no retriever (a router
that skips retrieval for small talk) is not recorded, so it cannot read as a false zero-hit.

## Review note (builder check, 2026-11-23)

A fresh reviewer read this design against the code. Fixed: there is no request without an
`InvocationContext` in 1.19.0 (the gap is null `invocationParameters`), and `Query.metadata()` can
be null. Settled as my calls: concurrent calls sharing `InvocationParameters` (counter marker);
mixed upstream/untracked final contents (record the untracked subset). The Quarkus wiring was
revised with the owner: automatic in both frameworks (D4), via a `@Dependent` producer and a
`Supplier` decorator rather than a helper bean or a deployment module.

## Left to the builder

Names of private helpers; exact WARN wording; test fixture shapes.
