package io.casehub.langchain4j.tracking;

import io.casehub.neocortex.rag.CorpusRef;
import io.casehub.neocortex.rag.FeedbackContext;
import io.casehub.neocortex.rag.RetrievalFeedback;
import io.casehub.neocortex.rag.RetrievalOutcome;
import io.casehub.neocortex.rag.RetrievalQuery;
import io.casehub.neocortex.rag.RetrievalRecord;
import io.casehub.neocortex.rag.RetrievalTracker;
import io.casehub.neocortex.rag.RetrievedChunk;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/** A {@link RetrievalTracker} that keeps what {@code record} received; shipped in the test-jar. */
public class RecordingTracker implements RetrievalTracker {

    /** One {@code record} call. */
    public record Recorded(RetrievalQuery query, CorpusRef corpus, List<RetrievedChunk> chunks, int maxResults, String id) {}

    public final List<Recorded> records = new CopyOnWriteArrayList<>();

    /** Thrown by {@code record} when set. */
    public volatile RuntimeException failure;

    @Override
    public String record(RetrievalQuery query, CorpusRef corpus, List<RetrievedChunk> results, int maxResults) {
        if (failure != null) {
            throw failure;
        }
        String id = "r-" + (records.size() + 1);
        records.add(new Recorded(query, corpus, List.copyOf(results), maxResults, id));
        return id;
    }

    @Override
    public void feedback(String retrievalId, String sourceDocumentId, RetrievalOutcome outcome, FeedbackContext context) {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<RetrievalRecord> findRecords(CorpusRef corpus, Instant since, Instant until) {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<RetrievalFeedback> findFeedback(CorpusRef corpus, Instant since, Instant until) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Set<String> findRetrievedDocumentIds(CorpusRef corpus, Instant since, Instant until) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int purgeOlderThan(Instant cutoff) {
        throw new UnsupportedOperationException();
    }
}
