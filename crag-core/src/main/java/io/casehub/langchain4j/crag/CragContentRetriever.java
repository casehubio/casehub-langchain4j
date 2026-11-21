package io.casehub.langchain4j.crag;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.ContentMetadata;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import io.casehub.neocortex.rag.RelevanceEvaluator;
import io.casehub.neocortex.rag.RelevanceGrade;
import io.casehub.neocortex.rag.RetrievalQuality;
import io.casehub.neocortex.rag.RetrievedChunk;
import io.casehub.neocortex.rag.ScoredGrade;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import org.jboss.logging.Logger;

/**
 * Corrective RAG for any {@link ContentRetriever}: grades what the delegate returns with a
 * {@link RelevanceEvaluator}, drops INCORRECT content, and puts CORRECT content before AMBIGUOUS
 * (keeping the delegate's order within each grade). Each surviving content carries its grade in the
 * segment metadata key {@value #GRADE_KEY} and, when the evaluator gives a finite score,
 * {@link ContentMetadata#RERANKED_SCORE}.
 *
 * <ul>
 *   <li>Content already graded upstream (every item carries a grade other than {@code UNGRADED}) is
 *       returned untouched.
 *   <li>An empty result is the right answer when nothing is relevant.
 *   <li>When the evaluator fails or breaks its contract, the retrieval is returned unfiltered with
 *       every item marked {@code UNGRADED}, so an outage of the model does not silently disable RAG.
 * </ul>
 *
 * <p>Unlike neocortex's own CRAG, there is no search expansion: {@code ContentRetriever} offers no
 * way to ask for more results.
 */
public class CragContentRetriever implements ContentRetriever {

    /** Segment metadata key holding the {@link RelevanceGrade} name. */
    public static final String GRADE_KEY = "relevance_grade";

    private static final String SOURCE_DOCUMENT_ID = "source_document_id";
    private static final Logger LOG = Logger.getLogger(CragContentRetriever.class);

    private final ContentRetriever delegate;
    private final RelevanceEvaluator evaluator;
    private final Consumer<RetrievalQuality> quality;

    public CragContentRetriever(ContentRetriever delegate, RelevanceEvaluator evaluator,
            Consumer<RetrievalQuality> quality) {
        this.delegate = Objects.requireNonNull(delegate, "delegate required");
        this.evaluator = Objects.requireNonNull(evaluator, "evaluator required");
        this.quality = Objects.requireNonNull(quality, "quality consumer required");
    }

    /** The retriever whose results are graded. */
    public ContentRetriever delegate() {
        return delegate;
    }

    @Override
    public List<Content> retrieve(Query query) {
        List<Content> contents = delegate.retrieve(query);
        if (contents == null || contents.isEmpty() || alreadyGraded(contents)) {
            return contents;
        }
        List<RetrievedChunk> chunks = new ArrayList<>(contents.size());
        for (int i = 0; i < contents.size(); i++) {
            chunks.add(toChunk(contents.get(i), i));
        }
        List<ScoredGrade> grades;
        try {
            grades = evaluator.evaluateChunks(query.text(), chunks);
            validate(grades, contents.size());
        } catch (RuntimeException e) {
            LOG.warnf(e, "CRAG: relevance evaluation failed — returning %d contents unfiltered and UNGRADED",
                    contents.size());
            publish(new RetrievalQuality(contents.size(), 0, 0, 0, false, false));
            return contents.stream().map(c -> graded(c, RelevanceGrade.UNGRADED, Float.NaN)).toList();
        }
        List<Content> correct = new ArrayList<>();
        List<Content> ambiguous = new ArrayList<>();
        int incorrect = 0;
        for (int i = 0; i < contents.size(); i++) {
            ScoredGrade grade = grades.get(i);
            switch (grade.grade()) {
                case CORRECT -> correct.add(graded(contents.get(i), grade.grade(), grade.score()));
                case AMBIGUOUS -> ambiguous.add(graded(contents.get(i), grade.grade(), grade.score()));
                default -> incorrect++;
            }
        }
        publish(new RetrievalQuality(contents.size(), correct.size(), ambiguous.size(), incorrect, true, false));
        List<Content> result = new ArrayList<>(correct);
        result.addAll(ambiguous);
        return result;
    }

    /**
     * Graded upstream only when every item carries CORRECT or AMBIGUOUS — a list holding INCORRECT,
     * UNGRADED, an unknown value or a stale grade is evaluated again.
     */
    private static boolean alreadyGraded(List<Content> contents) {
        for (Content content : contents) {
            Object grade = content.textSegment() == null ? null : content.textSegment().metadata().toMap().get(GRADE_KEY);
            String value = String.valueOf(grade);
            if (!value.equals(RelevanceGrade.CORRECT.name()) && !value.equals(RelevanceGrade.AMBIGUOUS.name())) {
                return false;
            }
        }
        return true;
    }

    private static void validate(List<ScoredGrade> grades, int expected) {
        if (grades == null || grades.size() != expected) {
            throw new IllegalStateException("evaluator returned " + (grades == null ? "null" : grades.size())
                    + " grades for " + expected + " contents");
        }
        for (ScoredGrade grade : grades) {
            if (grade == null || grade.grade() == null || grade.grade() == RelevanceGrade.UNGRADED) {
                throw new IllegalStateException("evaluator returned a missing or UNGRADED grade");
            }
        }
    }

    private static RetrievedChunk toChunk(Content content, int index) {
        TextSegment segment = content.textSegment();
        Map<String, String> metadata = new LinkedHashMap<>();
        segment.metadata().toMap().forEach((key, value) -> metadata.put(key, String.valueOf(value)));
        String sourceDocumentId = metadata.getOrDefault(SOURCE_DOCUMENT_ID, "content-" + index);
        Object score = content.metadata().get(ContentMetadata.SCORE);
        // NaN, not 0.0: a score-based evaluator (neocortex's ColBERT one) would grade a missing score
        // of 0.0 as INCORRECT and empty every retrieval from a retriever that reports no score.
        double relevanceScore = score instanceof Number number ? number.doubleValue() : Double.NaN;
        return new RetrievedChunk(segment.text(), sourceDocumentId, relevanceScore, metadata);
    }

    private static Content graded(Content content, RelevanceGrade grade, float score) {
        TextSegment segment = content.textSegment();
        Metadata metadata = segment.metadata().copy().put(GRADE_KEY, grade.name());
        Map<ContentMetadata, Object> contentMetadata = new LinkedHashMap<>(content.metadata());
        if (Float.isFinite(score)) {
            contentMetadata.put(ContentMetadata.RERANKED_SCORE, (double) score);  // LangChain4j stores Double
        }
        return Content.from(TextSegment.from(segment.text(), metadata), contentMetadata);
    }

    private void publish(RetrievalQuality summary) {
        try {
            quality.accept(summary);
        } catch (RuntimeException e) {
            LOG.warnf(e, "CRAG: quality listener failed — the retrieval is unaffected");
        }
    }
}
