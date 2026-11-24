package io.casehub.langchain4j.tracking;

import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import io.casehub.neocortex.rag.RetrievalRecorded;
import io.casehub.neocortex.rag.RetrievalTracker;
import java.util.List;
import java.util.Objects;
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
 * {@value #RETRIEVAL_ID_KEY} or neocortex's {@code _trackingId}) is not recorded again, and nothing
 * is recorded while a {@link TrackingRetrievalAugmentor} records the question this retrieval serves.
 */
public class TrackingContentRetriever implements ContentRetriever {

    /** Segment metadata key carrying the retrieval ID, for feedback. */
    public static final String RETRIEVAL_ID_KEY = "retrieval_id";

    static final String SOURCE_DOCUMENT_ID = RetrievalRecorder.SOURCE_DOCUMENT_ID;
    /** The key neocortex's own tracking decorator stamps on chunks. */
    static final String NEOCORTEX_TRACKING_ID = RetrievalRecorder.NEOCORTEX_TRACKING_ID;

    private static final Logger LOG = Logger.getLogger(TrackingContentRetriever.class);

    private final ContentRetriever delegate;
    private final RetrievalRecorder recorder;

    /**
     * @param tenantId the tenant to record under, read per retrieval; {@code null} or blank means the
     *        tenant cannot be determined and the retrieval is not recorded
     */
    public TrackingContentRetriever(ContentRetriever delegate, RetrievalTracker tracker, String corpusName,
            Supplier<String> tenantId, Consumer<RetrievalRecorded> recorded) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.recorder = new RetrievalRecorder(tracker, corpusName, tenantId, recorded, LOG);
    }

    /** The retriever this one records for. */
    public ContentRetriever delegate() {
        return delegate;
    }

    @Override
    public List<Content> retrieve(Query query) {
        List<Content> contents = delegate.retrieve(query);
        if (RetrievalRecorder.markedByAugmentor(query)) {
            RetrievalRecorder.countStoodDown(query);
            return contents;  // a TrackingRetrievalAugmentor records the question once
        }
        try {
            return recorder.record(query.text(), contents, false);
        } catch (RuntimeException e) {
            LOG.warnf(e, "Retrieval tracking failed — returning the retrieval unchanged");
            return contents;
        }
    }
}
