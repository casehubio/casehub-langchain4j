# Recipe — Query Rewriting (Multi-Query, HyDE, Step-Back)

Rewriting the user's question before retrieval — into several variants, into a hypothetical answer
(HyDE), or into a more general question (step-back) — often finds passages the literal question
misses. LangChain4j already does the rewriting: a `QueryTransformer` in a `DefaultRetrievalAugmentor`.
casehub-langchain4j has **no query-expansion module** (roadmap chapter C15 was dropped in #32).
What it adds is keeping its own concerns correct when one question becomes several queries:

- **Tracking** records each question **once**, under the user's message text, with the contents
  the model receives — not once per rewritten query.
- **De-duplication** keeps working: a passage several queries found reaches the model once, even
  though tracking stamps retrieval IDs on it.

The code below is in `examples/.../QueryRewritingExample.java`, tested offline by
`QueryRewritingExampleTest` (`mvn test -pl examples -am`).

---

## Ingredients

| What | Where | Why |
|---|---|---|
| A `QueryTransformer` | LangChain4j (`ExpandingQueryTransformer`) or your own | Does the rewriting |
| `RetrievalIdAwareContentAggregator` | `casehub-langchain4j-tracking-core` | Merges a passage found by several queries despite retrieval IDs |
| Augmentor tracking | `casehub-langchain4j-tracking` (Quarkus) / `-tracking-spring` | Records the question once; the retrievers inside stand down |

Augmentor tracking needs nothing from you when the augmentor is a bean: Spring wraps every
`RetrievalAugmentor` bean, and the Quarkus extension wraps the one quarkus-langchain4j's AI
services use (see the [consumer guide](consumer-guide.md#tracking--retrieval-tracking-and-analytics-for-any-retriever)
for the exact cases). An augmentor built inline is wrapped by hand with `TrackingRetrievalAugmentor`.

---

## Multi-query

The model writes variants of the question (three by default); each is retrieved and the results
are fused.

```java
@Produces
@ApplicationScoped
RetrievalAugmentor augmentor(ChatModel model, ContentRetriever retriever) {
    return DefaultRetrievalAugmentor.builder()
            .queryTransformer(new ExpandingQueryTransformer(model))
            .contentRetriever(retriever)
            .contentAggregator(new RetrievalIdAwareContentAggregator(new DefaultContentAggregator()))
            .build();
}
```

`ExpandingQueryTransformer` drops the original question and retrieves only the variants. Without
`RetrievalIdAwareContentAggregator` a passage two variants found is merged only when no retrieval
ID was stamped on it; with augmentor tracking the retrievers stand down and nothing is stamped
before aggregation, but the aggregator keeps the pipeline correct when they do record (an
augmentor that is not wrapped, or neocortex's hybrid search, which stamps its own `_trackingId`).

## HyDE — hypothetical document embeddings

A generated answer usually sits closer to the right passage in embedding space than the question
does. LangChain4j has no HyDE transformer, but it is a few lines:

```java
public final class HydeQueryTransformer implements QueryTransformer {

    private final ChatModel model;

    public HydeQueryTransformer(ChatModel model) {
        this.model = model;
    }

    @Override
    public List<Query> transform(Query query) {
        String passage = model.chat("""
                Write a short passage from documentation that answers the question below. \
                Write only the passage.

                Question: %s""".formatted(query.text()));
        return List.of(query, Query.from(passage, query.metadata()));
    }
}
```

Keeping the original query next to the hypothetical passage means a poor generation cannot lose
results; drop it if you want pure HyDE. Use it like `ExpandingQueryTransformer` above, with the
same aggregator.

## Step-back

Same shape as HyDE, with a different prompt: ask the model for the more general question behind
the user's ("What are the password policies?" for "Why was my new password rejected?") and
retrieve with both. Background passages found by the general question then sit next to the specific
ones.

---

## The one rule for your own transformer

**Build rewritten queries with the original query's metadata** — `Query.from(text, query.metadata())`,
as LangChain4j's own transformers do. The metadata carries the marker that tells tracked retrievers
an augmentor is recording this question. A query built with `Query.from(text)` loses it, and every
rewritten query is recorded at the retriever under its rewritten text instead of the question once
(`QueryRewritingExampleTest.transformerDroppingQueryMetadata_recordsEveryRewrittenQueryInstead`).

## What ends up in the tracker

| Pipeline | Records per question | Query text recorded |
|---|---|---|
| Multi-query, HyDE or step-back, metadata kept | 1 | The user's message |
| A transformer that drops the metadata | 1 per rewritten query | Each rewritten query |
| An augmentor that calls no retriever (a router skipping small talk) | 0 | — |

Each content the model receives carries that record's ID in its `retrieval_id` metadata, so
feedback (`RetrievalTracker.feedback`) goes to the question, not to one variant.

## CRAG and tenancy

Both wrap the retriever, so they run once per rewritten query: CRAG grades each passage against
the query that retrieved it (for HyDE, against the hypothetical passage — usually what you want),
and tenancy filters every query by the current tenant.
