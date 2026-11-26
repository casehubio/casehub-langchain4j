package io.casehub.langchain4j.examples;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.rag.DefaultRetrievalAugmentor;
import dev.langchain4j.rag.RetrievalAugmentor;
import dev.langchain4j.rag.content.aggregator.DefaultContentAggregator;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.rag.query.transformer.ExpandingQueryTransformer;
import dev.langchain4j.rag.query.transformer.QueryTransformer;
import io.casehub.langchain4j.tracking.RetrievalIdAwareContentAggregator;
import io.casehub.langchain4j.tracking.TrackingRetrievalAugmentor;
import io.casehub.neocortex.rag.RetrievalTracker;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Query rewriting with plain LangChain4j, tracked by casehub-langchain4j — the recipe in
 * {@code docs/guides/query-rewriting.md}. There is no query-expansion module: LangChain4j's
 * {@link QueryTransformer} does the rewriting, and casehub-langchain4j keeps tracking and passage
 * de-duplication correct when one question becomes several queries.
 */
public final class QueryRewritingExample {

    /** The HyDE prompt: answer first, then search with the answer. */
    static final String HYDE_PROMPT = """
            Write a short passage from documentation that answers the question below. \
            Write only the passage.

            Question: %s""";

    private QueryRewritingExample() {}

    /**
     * Multi-query: the model writes variants of the question, each is retrieved, the results are fused.
     * {@link RetrievalIdAwareContentAggregator} keeps a passage found by several variants from reaching
     * the model several times.
     */
    public static RetrievalAugmentor expanding(ChatModel model, ContentRetriever retriever) {
        return DefaultRetrievalAugmentor.builder()
                .queryTransformer(new ExpandingQueryTransformer(model))
                .contentRetriever(retriever)
                .contentAggregator(new RetrievalIdAwareContentAggregator(new DefaultContentAggregator()))
                .build();
    }

    /** HyDE: retrieves with the question and with a passage the model wrote to answer it. */
    public static RetrievalAugmentor hyde(ChatModel model, ContentRetriever retriever) {
        return DefaultRetrievalAugmentor.builder()
                .queryTransformer(new HydeQueryTransformer(model))
                .contentRetriever(retriever)
                .contentAggregator(new RetrievalIdAwareContentAggregator(new DefaultContentAggregator()))
                .build();
    }

    /**
     * Records each question once. Spring and Quarkus do this for a {@code RetrievalAugmentor} bean when
     * {@code casehub-langchain4j-tracking} is on the classpath; an augmentor built inline is wrapped by hand.
     */
    public static RetrievalAugmentor tracked(RetrievalAugmentor augmentor, RetrievalTracker tracker,
            Supplier<String> tenantId) {
        return new TrackingRetrievalAugmentor(augmentor, tracker, "default", tenantId, recorded -> {});
    }

    /**
     * Hypothetical document embeddings: a generated answer usually sits closer to the right passage than
     * the question does. The original query is kept, so a poor hypothetical passage cannot lose results.
     */
    public static final class HydeQueryTransformer implements QueryTransformer {

        private final ChatModel model;

        public HydeQueryTransformer(ChatModel model) {
            this.model = Objects.requireNonNull(model, "model");
        }

        @Override
        public List<Query> transform(Query query) {
            String passage = model.chat(HYDE_PROMPT.formatted(query.text()));
            // Keep the metadata: it carries the marker that makes tracking record the question once.
            return List.of(query, Query.from(passage, query.metadata()));
        }
    }
}
