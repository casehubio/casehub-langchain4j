package io.casehub.langchain4j.tracking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.ContentMetadata;
import dev.langchain4j.rag.content.aggregator.ContentAggregator;
import dev.langchain4j.rag.content.aggregator.DefaultContentAggregator;
import dev.langchain4j.rag.AugmentationRequest;
import dev.langchain4j.rag.DefaultRetrievalAugmentor;
import dev.langchain4j.rag.RetrievalAugmentor;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.rag.query.transformer.QueryTransformer;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RetrievalIdAwareContentAggregatorTest {

    private static Content passage(String text, String key, String id) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source_document_id", "doc-" + text);
        if (key != null) {
            metadata.put(key, id);
        }
        return Content.from(TextSegment.from(text, Metadata.from(metadata)), Map.of(ContentMetadata.SCORE, 0.5));
    }

    private static Content stamped(String text, String id) {
        return passage(text, TrackingContentRetriever.RETRIEVAL_ID_KEY, id);
    }

    private static Map<String, Object> segmentMetadata(Content content) {
        return content.textSegment().metadata().toMap();
    }

    private static Map<Query, Collection<List<Content>>> twoQueries(List<Content> first, List<Content> second) {
        Map<Query, Collection<List<Content>>> map = new LinkedHashMap<>();
        map.put(Query.from("q1"), List.of(first));
        map.put(Query.from("q2"), List.of(second));
        return map;
    }

    @Test
    void samePassageFromTwoRetrievals_mergedOnce_keepsFirstRetrievalId() {
        var aggregator = new RetrievalIdAwareContentAggregator(new DefaultContentAggregator());

        List<Content> result = aggregator.aggregate(twoQueries(
                List.of(stamped("shared", "r-1"), stamped("only-1", "r-1")),
                List.of(stamped("shared", "r-2"), stamped("only-2", "r-2"))));

        assertThat(result).extracting(c -> c.textSegment().text())
                .containsExactlyInAnyOrder("shared", "only-1", "only-2");
        assertThat(result).filteredOn(c -> c.textSegment().text().equals("shared")).singleElement()
                .satisfies(c -> assertThat(segmentMetadata(c))
                        .containsEntry(TrackingContentRetriever.RETRIEVAL_ID_KEY, "r-1")
                        .containsEntry("source_document_id", "doc-shared"));
        assertThat(result).filteredOn(c -> c.textSegment().text().equals("only-2")).singleElement()
                .satisfies(c -> assertThat(segmentMetadata(c))
                        .containsEntry(TrackingContentRetriever.RETRIEVAL_ID_KEY, "r-2"));
    }

    @Test
    void neocortexTrackingId_ignoredForMerging_andRestored() {
        var aggregator = new RetrievalIdAwareContentAggregator(new DefaultContentAggregator());

        List<Content> result = aggregator.aggregate(twoQueries(
                List.of(passage("shared", TrackingContentRetriever.NEOCORTEX_TRACKING_ID, "t-1")),
                List.of(stamped("shared", "r-2"))));

        assertThat(result).singleElement().satisfies(c -> assertThat(segmentMetadata(c))
                .containsEntry(TrackingContentRetriever.NEOCORTEX_TRACKING_ID, "t-1")
                .doesNotContainKey(TrackingContentRetriever.RETRIEVAL_ID_KEY));
    }

    @Test
    void delegateSeesContentsWithoutIds_andItsContentMetadataIsKept() {
        ContentAggregator delegate = queryToContents -> {
            Content first = queryToContents.values().iterator().next().iterator().next().get(0);
            assertThat(segmentMetadata(first)).doesNotContainKeys(
                    TrackingContentRetriever.RETRIEVAL_ID_KEY, TrackingContentRetriever.NEOCORTEX_TRACKING_ID);
            return List.of(Content.from(first.textSegment(), Map.of(ContentMetadata.RERANKED_SCORE, 0.9)));
        };

        List<Content> result = new RetrievalIdAwareContentAggregator(delegate)
                .aggregate(twoQueries(List.of(stamped("p", "r-1")), List.of()));

        assertThat(result).singleElement().satisfies(c -> {
            assertThat(segmentMetadata(c)).containsEntry(TrackingContentRetriever.RETRIEVAL_ID_KEY, "r-1");
            assertThat(c.metadata()).containsEntry(ContentMetadata.RERANKED_SCORE, 0.9);
        });
    }

    @Test
    void contentTheDelegateMadeUp_returnedUnchanged() {
        Content invented = passage("invented", null, null);
        ContentAggregator delegate = queryToContents -> List.of(invented);

        List<Content> result = new RetrievalIdAwareContentAggregator(delegate)
                .aggregate(twoQueries(List.of(stamped("p", "r-1")), List.of()));

        assertThat(result).containsExactly(invented);
        assertThat(segmentMetadata(result.get(0))).doesNotContainKey(TrackingContentRetriever.RETRIEVAL_ID_KEY);
    }

    @Test
    void untrackedContents_passThroughUnchanged() {
        var aggregator = new RetrievalIdAwareContentAggregator(new DefaultContentAggregator());
        Content plain = passage("plain", null, null);

        List<Content> result = aggregator.aggregate(twoQueries(List.of(plain), List.of(plain)));

        assertThat(result).containsExactly(plain);
        assertThat(segmentMetadata(result.get(0))).containsOnlyKeys("source_document_id");
    }

    @Test
    void expandingPipeline_trackedRetriever_eachPassageReachesTheModelOnce() {
        RecordingTracker tracker = new RecordingTracker();
        ContentRetriever app = query -> List.of(passage("shared", null, null), passage(query.text(), null, null));
        ContentRetriever tracked = new TrackingContentRetriever(app, tracker, "kb", () -> "a", e -> {});
        QueryTransformer expand = query -> List.of(Query.from("v1"), Query.from("v2"));
        RetrievalAugmentor augmentor = DefaultRetrievalAugmentor.builder()
                .queryTransformer(expand)
                .contentRetriever(tracked)
                .contentAggregator(new RetrievalIdAwareContentAggregator(new DefaultContentAggregator()))
                .executor(Runnable::run)
                .build();

        List<Content> contents = augmentor.augment(new AugmentationRequest(UserMessage.from("question"),
                        new dev.langchain4j.rag.query.Metadata(UserMessage.from("question"), null, List.of())))
                .contents();

        assertThat(tracker.records).hasSize(2);
        assertThat(contents).extracting(c -> c.textSegment().text()).containsExactlyInAnyOrder("shared", "v1", "v2");
        assertThat(contents).allSatisfy(c ->
                assertThat(segmentMetadata(c)).containsKey(TrackingContentRetriever.RETRIEVAL_ID_KEY));
    }

    @Test
    void nullDelegate_rejected() {
        assertThatThrownBy(() -> new RetrievalIdAwareContentAggregator(null)).isInstanceOf(NullPointerException.class);
    }
}
