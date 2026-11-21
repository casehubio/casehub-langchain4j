package io.casehub.langchain4j.tracking;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.ContentMetadata;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import io.casehub.neocortex.rag.CorpusRef;
import io.casehub.neocortex.rag.RetrievalQuery;
import io.casehub.neocortex.rag.RetrievalRecorded;
import io.casehub.neocortex.rag.RetrievalTracker;
import io.casehub.neocortex.rag.RetrievedChunk;
import io.casehub.neocortex.rag.RetrievedDocumentRef;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.jboss.logging.Logger;

/**
 * Records every retrieval of the wrapped {@link ContentRetriever} into neocortex's
 * {@link RetrievalTracker}, so neocortex's {@code RetrievalAnalyzer} works on LangChain4j retrievals.
 *
 * <p>Each content returned carries the retrieval ID in its segment metadata under
 * {@value #RETRIEVAL_ID_KEY}, for {@link RetrievalTracker#feedback}; each recorded retrieval is also
 * published as a {@link RetrievalRecorded}. Documents are identified by the segment metadata
 * {@code source_document_id}; content without it is returned but not recorded.
 *
 * <p>Tracking is purely observational: it never changes which contents are returned, and a tracking
 * failure is logged, never thrown. A retrieval already recorded upstream (a content carrying
 * {@value #RETRIEVAL_ID_KEY} or neocortex's {@code _trackingId}) is not recorded again.
 */
public class TrackingContentRetriever implements ContentRetriever {

    /** Segment metadata key carrying the retrieval ID, for feedback. */
    public static final String RETRIEVAL_ID_KEY = "retrieval_id";

    static final String SOURCE_DOCUMENT_ID = "source_document_id";
    /** The key neocortex's own tracking decorator stamps on chunks. */
    static final String NEOCORTEX_TRACKING_ID = "_trackingId";

    private static final Logger LOG = Logger.getLogger(TrackingContentRetriever.class);

    private final ContentRetriever delegate;
    private final RetrievalTracker tracker;
    private final String corpusName;
    private final Supplier<String> tenantId;
    private final Consumer<RetrievalRecorded> recorded;
    private final AtomicBoolean warnedMissingDocumentId = new AtomicBoolean();

    /**
     * @param tenantId the tenant to record under, read per retrieval; {@code null} or blank means the
     *        tenant cannot be determined and the retrieval is not recorded
     */
    public TrackingContentRetriever(ContentRetriever delegate, RetrievalTracker tracker, String corpusName,
            Supplier<String> tenantId, Consumer<RetrievalRecorded> recorded) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.tracker = Objects.requireNonNull(tracker, "tracker");
        this.corpusName = Objects.requireNonNull(corpusName, "corpusName");
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        this.recorded = Objects.requireNonNull(recorded, "recorded");
    }

    /** The retriever this one records for. */
    public ContentRetriever delegate() {
        return delegate;
    }

    @Override
    public List<Content> retrieve(Query query) {
        List<Content> contents = delegate.retrieve(query);
        try {
            return track(query, contents);
        } catch (RuntimeException e) {
            LOG.warnf(e, "Retrieval tracking failed — returning the retrieval unchanged");
            return contents;
        }
    }

    private List<Content> track(Query query, List<Content> contents) {
        if (contents.stream().anyMatch(TrackingContentRetriever::alreadyTracked)) {
            return contents.stream().map(TrackingContentRetriever::withUpstreamId).toList();
        }
        String text = query.text();
        if (text == null || text.isBlank()) {
            return contents;
        }
        String tenant = tenantId.get();
        if (tenant == null || tenant.isBlank()) {
            return contents;
        }
        List<RetrievedChunk> chunks = new ArrayList<>();
        boolean missingDocumentId = false;
        for (Content content : contents) {
            TextSegment segment = content.textSegment();
            if (segment == null) {
                continue;
            }
            RetrievedChunk chunk = toChunk(content, segment);
            if (chunk == null) {
                missingDocumentId = true;
            } else {
                chunks.add(chunk);
            }
        }
        if (missingDocumentId && warnedMissingDocumentId.compareAndSet(false, true)) {
            LOG.warnf("Retrieval tracking: content without segment metadata '%s' is returned but not recorded"
                    + " — set it at ingestion to track those documents", SOURCE_DOCUMENT_ID);
        }
        if (!contents.isEmpty() && chunks.isEmpty()) {
            return contents;  // an empty record here would read as a false zero-hit query
        }
        RetrievalQuery retrievalQuery = RetrievalQuery.of(text);
        CorpusRef corpus = new CorpusRef(tenant, corpusName);
        String retrievalId = tracker.record(retrievalQuery, corpus, chunks, contents.size());
        List<Content> stamped = contents.stream().map(c -> stamp(c, retrievalId)).toList();
        try {
            recorded.accept(new RetrievalRecorded(retrievalId, retrievalQuery, corpus, documentRefs(chunks)));
        } catch (RuntimeException e) {
            LOG.warnf(e, "Retrieval tracking: RetrievalRecorded listener failed — retrieval %s is recorded", retrievalId);
        }
        return stamped;
    }

    private static boolean alreadyTracked(Content content) {
        TextSegment segment = content.textSegment();
        if (segment == null) {
            return false;
        }
        Map<String, Object> metadata = segment.metadata().toMap();
        return metadata.containsKey(RETRIEVAL_ID_KEY) || metadata.containsKey(NEOCORTEX_TRACKING_ID);
    }

    /** Copies neocortex's {@code _trackingId} into {@value #RETRIEVAL_ID_KEY}, so callers read one key. */
    private static Content withUpstreamId(Content content) {
        TextSegment segment = content.textSegment();
        if (segment == null) {
            return content;
        }
        Map<String, Object> metadata = segment.metadata().toMap();
        Object upstream = metadata.get(NEOCORTEX_TRACKING_ID);
        if (upstream == null || metadata.containsKey(RETRIEVAL_ID_KEY)) {
            return content;
        }
        return stamp(content, String.valueOf(upstream));
    }

    private static RetrievedChunk toChunk(Content content, TextSegment segment) {
        Map<String, String> metadata = new LinkedHashMap<>();
        segment.metadata().toMap().forEach((key, value) -> metadata.put(key, String.valueOf(value)));
        String sourceDocumentId = metadata.get(SOURCE_DOCUMENT_ID);
        if (sourceDocumentId == null || sourceDocumentId.isBlank()) {
            return null;
        }
        return new RetrievedChunk(segment.text(), sourceDocumentId, score(content), metadata);
    }

    /** Re-ranked score, else retrieval score, else 0.0 — never NaN (neocortex's SQLite schema rejects it). */
    private static double score(Content content) {
        Map<ContentMetadata, Object> metadata = content.metadata();
        if (metadata == null) {
            return 0.0;
        }
        for (ContentMetadata key : List.of(ContentMetadata.RERANKED_SCORE, ContentMetadata.SCORE)) {
            if (metadata.get(key) instanceof Number number && Double.isFinite(number.doubleValue())) {
                return number.doubleValue();
            }
        }
        return 0.0;
    }

    /** One ref per document, its highest score — neocortex's collapse rule. */
    private static List<RetrievedDocumentRef> documentRefs(List<RetrievedChunk> chunks) {
        Map<String, Double> best = new LinkedHashMap<>();
        chunks.forEach(c -> best.merge(c.sourceDocumentId(), c.relevanceScore(), Math::max));
        return best.entrySet().stream().map(e -> new RetrievedDocumentRef(e.getKey(), e.getValue())).toList();
    }

    private static Content stamp(Content content, String retrievalId) {
        TextSegment segment = content.textSegment();
        if (segment == null) {
            return content;
        }
        TextSegment stamped = TextSegment.from(segment.text(), segment.metadata().copy().put(RETRIEVAL_ID_KEY, retrievalId));
        Map<ContentMetadata, Object> metadata = content.metadata() == null ? Map.of() : content.metadata();
        return Content.from(stamped, new LinkedHashMap<>(metadata));
    }
}
