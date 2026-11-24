package io.casehub.langchain4j.tracking;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.invocation.InvocationParameters;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.ContentMetadata;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.jboss.logging.Logger;

/**
 * Records one retrieval into neocortex's {@link RetrievalTracker} and stamps the contents with its
 * ID — the logic {@link TrackingContentRetriever} and {@link TrackingRetrievalAugmentor} share.
 */
final class RetrievalRecorder {

    static final String SOURCE_DOCUMENT_ID = "source_document_id";
    /** The key neocortex's own tracking decorator stamps on chunks. */
    static final String NEOCORTEX_TRACKING_ID = "_trackingId";

    private final RetrievalTracker tracker;
    private final String corpusName;
    private final Supplier<String> tenantId;
    private final Consumer<RetrievalRecorded> recorded;
    private final Logger log;
    private final AtomicBoolean warnedMissingDocumentId = new AtomicBoolean();

    RetrievalRecorder(RetrievalTracker tracker, String corpusName, Supplier<String> tenantId,
            Consumer<RetrievalRecorded> recorded, Logger log) {
        this.tracker = Objects.requireNonNull(tracker, "tracker");
        this.corpusName = Objects.requireNonNull(corpusName, "corpusName");
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        this.recorded = Objects.requireNonNull(recorded, "recorded");
        this.log = log;
    }

    /** Counter of retrievals that stood down for a {@link TrackingRetrievalAugmentor}. */
    static final String RETRIEVALS_KEY = TrackingRetrievalAugmentor.TRACKED_BY_AUGMENTOR_KEY + ".retrievals";

    /** Whether {@link TrackingRetrievalAugmentor} is recording the question this query belongs to. */
    static boolean markedByAugmentor(Query query) {
        InvocationParameters parameters = parameters(query);
        return parameters != null && parameters.containsKey(TrackingRetrievalAugmentor.TRACKED_BY_AUGMENTOR_KEY);
    }

    /** Tells the augmentor a retrieval ran (and stood down) for its question. */
    static void countStoodDown(Query query) {
        InvocationParameters parameters = parameters(query);
        if (parameters == null) {
            return;
        }
        synchronized (parameters) {
            AtomicInteger retrievals = parameters.get(RETRIEVALS_KEY);
            if (retrievals != null) {
                retrievals.incrementAndGet();
            }
        }
    }

    private static InvocationParameters parameters(Query query) {
        if (query == null || query.metadata() == null) {
            return null;
        }
        InvocationContext context = query.metadata().invocationContext();
        return context == null ? null : context.invocationParameters();
    }

    /**
     * Records {@code contents} as one retrieval of {@code queryText} and returns them stamped with its ID.
     *
     * @param recordUntrackedSubset {@code false}: any content already recorded upstream means nothing
     *        is recorded; {@code true}: only the contents not recorded upstream are recorded
     */
    List<Content> record(String queryText, List<Content> contents, boolean recordUntrackedSubset) {
        boolean anyUpstream = contents.stream().anyMatch(RetrievalRecorder::alreadyTracked);
        if (anyUpstream && (!recordUntrackedSubset || contents.stream().allMatch(RetrievalRecorder::alreadyTracked))) {
            return contents.stream().map(RetrievalRecorder::withUpstreamId).toList();
        }
        List<Content> toRecord = anyUpstream
                ? contents.stream().filter(c -> !alreadyTracked(c)).toList()
                : contents;
        if (queryText == null || queryText.isBlank()) {
            return contents;
        }
        String tenant = tenantId.get();
        if (tenant == null || tenant.isBlank()) {
            return contents;
        }
        List<RetrievedChunk> chunks = new ArrayList<>();
        boolean missingDocumentId = false;
        for (Content content : toRecord) {
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
            log.warnf("Retrieval tracking: content without segment metadata '%s' is returned but not recorded"
                    + " — set it at ingestion to track those documents", SOURCE_DOCUMENT_ID);
        }
        if (!toRecord.isEmpty() && chunks.isEmpty()) {
            return contents.stream().map(RetrievalRecorder::withUpstreamId).toList();  // an empty record would read as a false zero-hit query
        }
        RetrievalQuery retrievalQuery = RetrievalQuery.of(queryText);
        CorpusRef corpus = new CorpusRef(tenant, corpusName);
        String retrievalId = tracker.record(retrievalQuery, corpus, chunks, toRecord.size());
        List<Content> stamped = contents.stream()
                .map(c -> alreadyTracked(c) ? withUpstreamId(c) : stamp(c, retrievalId))
                .toList();
        try {
            recorded.accept(new RetrievalRecorded(retrievalId, retrievalQuery, corpus, documentRefs(chunks)));
        } catch (RuntimeException e) {
            log.warnf(e, "Retrieval tracking: RetrievalRecorded listener failed — retrieval %s is recorded", retrievalId);
        }
        return stamped;
    }

    static boolean alreadyTracked(Content content) {
        TextSegment segment = content.textSegment();
        if (segment == null) {
            return false;
        }
        Map<String, Object> metadata = segment.metadata().toMap();
        return metadata.containsKey(TrackingContentRetriever.RETRIEVAL_ID_KEY) || metadata.containsKey(NEOCORTEX_TRACKING_ID);
    }

    /** Copies neocortex's {@code _trackingId} into {@code retrieval_id}, so callers read one key. */
    private static Content withUpstreamId(Content content) {
        TextSegment segment = content.textSegment();
        if (segment == null) {
            return content;
        }
        Map<String, Object> metadata = segment.metadata().toMap();
        Object upstream = metadata.get(NEOCORTEX_TRACKING_ID);
        if (upstream == null || metadata.containsKey(TrackingContentRetriever.RETRIEVAL_ID_KEY)) {
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
        TextSegment stamped = TextSegment.from(segment.text(),
                segment.metadata().copy().put(TrackingContentRetriever.RETRIEVAL_ID_KEY, retrievalId));
        Map<ContentMetadata, Object> metadata = content.metadata() == null ? Map.of() : content.metadata();
        return Content.from(stamped, new LinkedHashMap<>(metadata));
    }
}
