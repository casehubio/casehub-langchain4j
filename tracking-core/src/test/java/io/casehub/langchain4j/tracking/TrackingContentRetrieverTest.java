package io.casehub.langchain4j.tracking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.ContentMetadata;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import io.casehub.neocortex.rag.CorpusRef;
import io.casehub.neocortex.rag.RetrievalRecorded;
import io.casehub.neocortex.rag.RetrievedChunk;
import io.casehub.neocortex.rag.RetrievedDocumentRef;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class TrackingContentRetrieverTest {

    private final RecordingTracker tracker = new RecordingTracker();
    private final List<RetrievalRecorded> events = new ArrayList<>();

    private TrackingContentRetriever tracking(List<Content> result) {
        return tracking(query -> result, () -> "a");
    }

    private TrackingContentRetriever tracking(ContentRetriever delegate, Supplier<String> tenant) {
        return new TrackingContentRetriever(delegate, tracker, "kb", tenant, events::add);
    }

    private static Content doc(String text, String docId, Double score) {
        Metadata metadata = docId == null ? new Metadata() : Metadata.from("source_document_id", docId);
        Map<ContentMetadata, Object> contentMetadata = new HashMap<>();
        if (score != null) {
            contentMetadata.put(ContentMetadata.SCORE, score);
        }
        return Content.from(TextSegment.from(text, metadata), contentMetadata);
    }

    private static Content withSegmentMetadata(String text, String key, Object value) {
        Map<String, Object> map = new HashMap<>();
        map.put("source_document_id", "doc-" + text);
        map.put(key, value);
        return Content.from(TextSegment.from(text, Metadata.from(map)));
    }

    private static Object retrievalId(Content content) {
        return content.textSegment().metadata().toMap().get(TrackingContentRetriever.RETRIEVAL_ID_KEY);
    }

    private static List<String> warningsDuring(Runnable action) {
        java.util.logging.Logger logger = java.util.logging.Logger.getLogger(TrackingContentRetriever.class.getName());
        List<String> messages = new CopyOnWriteArrayList<>();
        java.util.logging.Handler handler = new java.util.logging.Handler() {
            @Override
            public void publish(java.util.logging.LogRecord record) {
                if (record.getLevel().intValue() >= java.util.logging.Level.WARNING.intValue()) {
                    messages.add(String.valueOf(record.getMessage()));
                }
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        logger.addHandler(handler);
        try {
            action.run();
        } finally {
            logger.removeHandler(handler);
        }
        return messages;
    }

    @Test
    void recordsQueryTenantCorpusAndDocuments() {
        tracking(List.of(doc("one", "doc-1", 0.8), doc("two", "doc-2", 0.5))).retrieve(Query.from("q"));

        assertThat(tracker.records).hasSize(1);
        RecordingTracker.Recorded recorded = tracker.records.get(0);
        assertThat(recorded.query().text()).isEqualTo("q");
        assertThat(recorded.corpus()).isEqualTo(new CorpusRef("a", "kb"));
        assertThat(recorded.chunks()).extracting(RetrievedChunk::sourceDocumentId).containsExactly("doc-1", "doc-2");
        assertThat(recorded.chunks()).extracting(RetrievedChunk::relevanceScore).containsExactly(0.8, 0.5);
        assertThat(recorded.chunks()).extracting(RetrievedChunk::content).containsExactly("one", "two");
        assertThat(recorded.maxResults()).isEqualTo(2);
    }

    @Test
    void scorePrecedence() {
        Content reranked = Content.from(TextSegment.from("r", Metadata.from("source_document_id", "d1")),
                Map.of(ContentMetadata.RERANKED_SCORE, 0.9, ContentMetadata.SCORE, 0.4));
        Content scoreOnly = doc("s", "d2", 0.4);
        Content nanReranked = Content.from(TextSegment.from("n", Metadata.from("source_document_id", "d3")),
                Map.of(ContentMetadata.RERANKED_SCORE, Double.NaN, ContentMetadata.SCORE, 0.4));
        Content none = doc("x", "d4", null);
        Content floatReranked = Content.from(TextSegment.from("f", Metadata.from("source_document_id", "d5")),
                Map.of(ContentMetadata.RERANKED_SCORE, 0.75f));

        tracking(List.of(reranked, scoreOnly, nanReranked, none, floatReranked)).retrieve(Query.from("q"));

        assertThat(tracker.records.get(0).chunks()).extracting(RetrievedChunk::relevanceScore)
                .containsExactly(0.9, 0.4, 0.4, 0.0, 0.75);
    }

    @Test
    void allContentsReturnedInOrder_stampedWithRetrievalId() {
        Content first = Content.from(TextSegment.from("one", Metadata.from(Map.of("source_document_id", "doc-1", "lang", "en"))),
                Map.of(ContentMetadata.SCORE, 0.8));
        Content anonymous = doc("anon", null, 0.3);

        List<Content> result = tracking(List.of(first, anonymous)).retrieve(Query.from("q"));

        assertThat(result).extracting(c -> c.textSegment().text()).containsExactly("one", "anon");
        assertThat(result).extracting(TrackingContentRetrieverTest::retrievalId).containsExactly("r-1", "r-1");
        assertThat(result.get(0).textSegment().metadata().getString("lang")).isEqualTo("en");
        assertThat(result.get(0).metadata()).containsEntry(ContentMetadata.SCORE, 0.8);
        assertThat(tracker.records.get(0).chunks()).extracting(RetrievedChunk::sourceDocumentId).containsExactly("doc-1");
    }

    @Test
    void missingSourceDocumentId_warnsOnce() {
        TrackingContentRetriever retriever = tracking(List.of(doc("one", "doc-1", 0.8), doc("anon", null, 0.3)));

        List<String> warnings = warningsDuring(() -> {
            retriever.retrieve(Query.from("q1"));
            retriever.retrieve(Query.from("q2"));
        });

        assertThat(warnings).filteredOn(m -> m.contains("source_document_id")).hasSize(1);
    }

    @Test
    void eventPublished() {
        Query query = Query.from("q");
        tracking(List.of(doc("one", "doc-1", 0.8))).retrieve(query);

        assertThat(events).hasSize(1);
        RetrievalRecorded event = events.get(0);
        assertThat(event.retrievalId()).isEqualTo("r-1");
        assertThat(event.query().text()).isEqualTo("q");
        assertThat(event.corpus()).isEqualTo(new CorpusRef("a", "kb"));
        assertThat(event.documents()).containsExactly(new RetrievedDocumentRef("doc-1", 0.8));
    }

    @Test
    void duplicateDocument_collapsedInEvent_allStamped() {
        List<Content> result = tracking(List.of(doc("one", "doc-1", 0.4), doc("two", "doc-1", 0.9)))
                .retrieve(Query.from("q"));

        assertThat(events.get(0).documents()).containsExactly(new RetrievedDocumentRef("doc-1", 0.9));
        assertThat(result).extracting(TrackingContentRetrieverTest::retrievalId).containsExactly("r-1", "r-1");
    }

    @Test
    void emptyResult_recordedWithNoChunks() {
        List<Content> result = tracking(List.of()).retrieve(Query.from("q"));

        assertThat(result).isEmpty();
        assertThat(tracker.records).hasSize(1);
        assertThat(tracker.records.get(0).chunks()).isEmpty();
        assertThat(tracker.records.get(0).maxResults()).isZero();
        assertThat(events).hasSize(1);
        assertThat(events.get(0).documents()).isEmpty();
    }

    @Test
    void nonEmptyWithoutAnySourceDocumentId_notRecorded() {
        List<Content> input = List.of(doc("anon", null, 0.3));

        List<Content> result = tracking(input).retrieve(Query.from("q"));

        assertThat(tracker.records).isEmpty();
        assertThat(events).isEmpty();
        assertThat(result).isEqualTo(input);
    }

    @Test
    void alreadyStamped_anyContent_notRecorded() {
        List<Content> input = List.of(doc("one", "doc-1", 0.8), withSegmentMetadata("two", "retrieval_id", "r-old"));

        List<Content> result = tracking(input).retrieve(Query.from("q"));

        assertThat(tracker.records).isEmpty();
        assertThat(events).isEmpty();
        assertThat(result).isEqualTo(input);
    }

    @Test
    void neocortexTrackingId_copiedToRetrievalId_notRecorded() {
        List<Content> result = tracking(List.of(withSegmentMetadata("one", "_trackingId", "t-9"), doc("two", "doc-2", 0.5)))
                .retrieve(Query.from("q"));

        assertThat(tracker.records).isEmpty();
        assertThat(retrievalId(result.get(0))).isEqualTo("t-9");
        assertThat(retrievalId(result.get(1))).isNull();
        assertThat(result).extracting(c -> c.textSegment().text()).containsExactly("one", "two");
    }

    @Test
    void trackingIdNonString_copiedAsString() {
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000009");

        List<Content> result = tracking(List.of(withSegmentMetadata("one", "_trackingId", id))).retrieve(Query.from("q"));

        assertThat(tracker.records).isEmpty();
        assertThat(retrievalId(result.get(0))).isEqualTo(id.toString());
    }

    @Test
    void blankQueryText_notRecorded() {
        List<Content> input = List.of(doc("one", "doc-1", 0.8));

        // Query.from rejects blank text; a custom Query subclass or mock can still carry one.
        Query blank = org.mockito.Mockito.mock(Query.class);
        org.mockito.Mockito.when(blank.text()).thenReturn(" ");

        List<Content> result = tracking(input).retrieve(blank);

        assertThat(tracker.records).isEmpty();
        assertThat(result).isEqualTo(input);
    }

    @Test
    void nullTenant_notRecorded() {
        List<Content> input = List.of(doc("one", "doc-1", 0.8));

        List<Content> result = tracking(query -> input, () -> null).retrieve(Query.from("q"));

        assertThat(tracker.records).isEmpty();
        assertThat(result).isEqualTo(input);
    }

    @Test
    void contentWithoutSegment_returnedUntouched() {
        Content noSegment = new Content() {
            @Override
            public TextSegment textSegment() {
                return null;
            }

            @Override
            public Map<ContentMetadata, Object> metadata() {
                return Map.of();
            }
        };

        List<Content> result = tracking(List.of(noSegment, doc("one", "doc-1", 0.8))).retrieve(Query.from("q"));

        assertThat(result.get(0)).isSameAs(noSegment);
        assertThat(retrievalId(result.get(1))).isEqualTo("r-1");
        assertThat(tracker.records.get(0).chunks()).extracting(RetrievedChunk::sourceDocumentId).containsExactly("doc-1");
    }

    @Test
    void trackerThrows_returnsOriginal_noEvent() {
        tracker.failure = new IllegalStateException("tracker down");
        List<Content> input = List.of(doc("one", "doc-1", 0.8));

        List<Content> result = tracking(input).retrieve(Query.from("q"));

        assertThat(result).isEqualTo(input);
        assertThat(events).isEmpty();
    }

    @Test
    void eventConsumerThrows_returnsStamped() {
        TrackingContentRetriever retriever = new TrackingContentRetriever(query -> List.of(doc("one", "doc-1", 0.8)),
                tracker, "kb", () -> "a", event -> {
                    throw new IllegalStateException("observer failed");
                });

        List<Content> result = retriever.retrieve(Query.from("q"));

        assertThat(retrievalId(result.get(0))).isEqualTo("r-1");
    }

    @Test
    void tenantSupplierThrows_returnsOriginal() {
        List<Content> input = List.of(doc("one", "doc-1", 0.8));

        List<Content> result = tracking(query -> input, () -> {
            throw new IllegalStateException("principal broken");
        }).retrieve(Query.from("q"));

        assertThat(result).isEqualTo(input);
        assertThat(tracker.records).isEmpty();
    }

    @Test
    void delegateThrows_propagates() {
        IllegalStateException failure = new IllegalStateException("store down");

        assertThatThrownBy(() -> tracking(query -> {
            throw failure;
        }, () -> "a").retrieve(Query.from("q"))).isSameAs(failure);
    }

    @Test
    void delegate_isExposed() {
        ContentRetriever delegate = query -> List.of();

        assertThat(tracking(delegate, () -> "a").delegate()).isSameAs(delegate);
    }
}
