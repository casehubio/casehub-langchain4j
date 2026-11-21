package io.casehub.langchain4j.crag;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class CragContentRetrieverTest {

    /** Grades by text prefix and records what it was asked. */
    static class FakeEvaluator implements RelevanceEvaluator {
        final List<String> queries = new ArrayList<>();
        final List<List<RetrievedChunk>> calls = new ArrayList<>();
        Function<List<RetrievedChunk>, List<ScoredGrade>> override;

        @Override
        public List<ScoredGrade> evaluateChunks(String query, List<RetrievedChunk> chunks) {
            queries.add(query);
            calls.add(chunks);
            if (override != null) {
                return override.apply(chunks);
            }
            return chunks.stream().map(c -> {
                if (c.content().startsWith("good")) {
                    return new ScoredGrade(RelevanceGrade.CORRECT, 0.9f);
                }
                if (c.content().startsWith("maybe")) {
                    return new ScoredGrade(RelevanceGrade.AMBIGUOUS, 0.5f);
                }
                return new ScoredGrade(RelevanceGrade.INCORRECT, 0.1f);
            }).toList();
        }
    }

    private final FakeEvaluator evaluator = new FakeEvaluator();
    private final List<RetrievalQuality> qualities = new ArrayList<>();

    private static Content content(String text) {
        return Content.from(TextSegment.from(text));
    }

    private List<Content> retrieve(List<Content> input) {
        ContentRetriever delegate = query -> input;
        return new CragContentRetriever(delegate, evaluator, qualities::add).retrieve(Query.from("what?"));
    }

    private static List<String> texts(List<Content> contents) {
        return contents.stream().map(c -> c.textSegment().text()).toList();
    }

    private static String grade(Content content) {
        return content.textSegment().metadata().getString("relevance_grade");
    }

    @Test
    void dropsIncorrect_putsCorrectFirst_keepsOrderWithinGrade() {
        List<Content> result = retrieve(List.of(
                content("maybe1"), content("good1"), content("bad1"), content("good2"), content("maybe2")));

        assertThat(texts(result)).containsExactly("good1", "good2", "maybe1", "maybe2");
    }

    @Test
    void attachesGradeAndFiniteScore() {
        List<Content> result = retrieve(List.of(content("good1"), content("maybe1")));

        assertThat(grade(result.get(0))).isEqualTo("CORRECT");
        assertThat(result.get(0).metadata()).containsEntry(ContentMetadata.RERANKED_SCORE, (double) 0.9f);
        assertThat(grade(result.get(1))).isEqualTo("AMBIGUOUS");
    }

    @Test
    void nanScore_isNotAttached() {
        evaluator.override = chunks -> chunks.stream().map(c -> new ScoredGrade(RelevanceGrade.CORRECT, Float.NaN)).toList();

        List<Content> result = retrieve(List.of(content("good1")));

        assertThat(result.get(0).metadata()).doesNotContainKey(ContentMetadata.RERANKED_SCORE);
        assertThat(grade(result.get(0))).isEqualTo("CORRECT");
    }

    @Test
    void keepsOriginalMetadataAndScore() {
        Content original = Content.from(
                TextSegment.from("good1", Metadata.from("title", "Guide")), Map.of(ContentMetadata.SCORE, 0.42));

        Content result = retrieve(List.of(original)).get(0);

        assertThat(result.textSegment().metadata().getString("title")).isEqualTo("Guide");
        assertThat(result.metadata()).containsEntry(ContentMetadata.SCORE, 0.42);
        assertThat(evaluator.calls.get(0).get(0).relevanceScore()).isEqualTo(0.42);
    }

    @Test
    void allIncorrect_returnsEmpty_andPublishesQuality() {
        List<Content> result = retrieve(List.of(content("bad1"), content("bad2"), content("bad3")));

        assertThat(result).isEmpty();
        assertThat(qualities).containsExactly(new RetrievalQuality(3, 0, 0, 3, true, false));
    }

    @Test
    void emptyResult_noEvaluation_noEvent() {
        assertThat(retrieve(List.of())).isEmpty();
        assertThat(evaluator.calls).isEmpty();
        assertThat(qualities).isEmpty();
    }

    @Test
    void alreadyGraded_skipsEvaluator_noEvent() {
        List<Content> graded = List.of(
                Content.from(TextSegment.from("x", Metadata.from("relevance_grade", "CORRECT"))),
                Content.from(TextSegment.from("y", Metadata.from("relevance_grade", "AMBIGUOUS"))));

        assertThat(retrieve(graded)).isSameAs(graded);
        assertThat(evaluator.calls).isEmpty();
        assertThat(qualities).isEmpty();
    }

    @Test
    void mixedGradedAndUngraded_evaluatesAll() {
        retrieve(List.of(
                Content.from(TextSegment.from("good1", Metadata.from("relevance_grade", "CORRECT"))),
                content("bad1")));

        assertThat(evaluator.calls.get(0)).hasSize(2);
    }

    @Test
    void ungradedMarker_doesNotCountAsGraded() {
        retrieve(List.of(Content.from(TextSegment.from("good1", Metadata.from("relevance_grade", "UNGRADED")))));

        assertThat(evaluator.calls).hasSize(1);
    }

    @Test
    void duplicateContent_gradedIndependently() {
        List<Content> result = retrieve(List.of(content("good1"), content("good1")));

        assertThat(evaluator.calls.get(0)).hasSize(2);
        assertThat(texts(result)).containsExactly("good1", "good1");
    }

    @Test
    void missingSourceDocumentId_usesSyntheticIds_otherwiseKeepsIt() {
        retrieve(List.of(content("good1"),
                Content.from(TextSegment.from("good2", Metadata.from("source_document_id", "doc-7")))));

        assertThat(evaluator.calls.get(0)).extracting(RetrievedChunk::sourceDocumentId)
                .containsExactly("content-0", "doc-7");
    }

    @Test
    void nonStringMetadata_convertedForEvaluator_originalKept() {
        UUID id = UUID.randomUUID();
        Metadata metadata = new Metadata().put("page", 3).put("ref", id);

        Content result = retrieve(List.of(Content.from(TextSegment.from("good1", metadata)))).get(0);

        assertThat(evaluator.calls.get(0).get(0).metadata()).containsEntry("page", "3").containsEntry("ref", id.toString());
        assertThat(result.textSegment().metadata().getInteger("page")).isEqualTo(3);
        assertThat(result.textSegment().metadata().getUUID("ref")).isEqualTo(id);
    }

    @Test
    void evaluatorReceivesQueryText() {
        retrieve(List.of(content("good1")));

        assertThat(evaluator.queries).containsExactly("what?");
    }

    private void assertUnfilteredUngraded(List<Content> result) {
        assertThat(texts(result)).containsExactly("good1", "bad1");
        assertThat(result).allSatisfy(c -> assertThat(grade(c)).isEqualTo("UNGRADED"));
        assertThat(qualities).singleElement().satisfies(q -> assertThat(q.evaluated()).isFalse());
    }

    @Test
    void evaluatorThrows_returnsUnfilteredUngraded_evaluatedFalse() {
        evaluator.override = chunks -> {
            throw new IllegalStateException("model down");
        };

        assertUnfilteredUngraded(retrieve(List.of(content("good1"), content("bad1"))));
    }

    @Test
    void evaluatorWrongSize_returnsUnfilteredUngraded() {
        evaluator.override = chunks -> List.of(new ScoredGrade(RelevanceGrade.CORRECT, 1f));

        assertUnfilteredUngraded(retrieve(List.of(content("good1"), content("bad1"))));
    }

    @Test
    void evaluatorUngradedGrade_returnsUnfilteredUngraded() {
        evaluator.override = chunks -> chunks.stream().map(c -> new ScoredGrade(RelevanceGrade.UNGRADED, 0f)).toList();

        assertUnfilteredUngraded(retrieve(List.of(content("good1"), content("bad1"))));
    }

    @Test
    void evaluatorNullEntry_returnsUnfilteredUngraded() {
        evaluator.override = chunks -> {
            List<ScoredGrade> grades = new ArrayList<>();
            grades.add(new ScoredGrade(RelevanceGrade.CORRECT, 1f));
            grades.add(null);
            return grades;
        };

        assertUnfilteredUngraded(retrieve(List.of(content("good1"), content("bad1"))));
    }

    @Test
    void qualityConsumerThrows_retrievalStillReturned() {
        ContentRetriever delegate = query -> List.of(content("good1"));
        CragContentRetriever crag = new CragContentRetriever(delegate, evaluator, q -> {
            throw new IllegalStateException("observer broke");
        });

        assertThat(texts(crag.retrieve(Query.from("q")))).containsExactly("good1");
    }

    @Test
    void exposesDelegate() {
        ContentRetriever delegate = query -> List.of();

        assertThat(new CragContentRetriever(delegate, evaluator, q -> {}).delegate()).isSameAs(delegate);
    }

    @Test
    void qualityEventType_wrapsTheSummary() {
        RetrievalQuality quality = new RetrievalQuality(1, 1, 0, 0, true, false);

        assertThat(new CragRetrievalQuality(quality).quality()).isSameAs(quality);
    }

    @Test
    void missingScore_isSentAsNaN_notZero() {
        retrieve(List.of(content("good1")));

        assertThat(evaluator.calls.get(0).get(0).relevanceScore()).isNaN();
    }

    @Test
    void fullyGradedListContainingIncorrect_isEvaluated() {
        retrieve(List.of(
                Content.from(TextSegment.from("good1", Metadata.from("relevance_grade", "CORRECT"))),
                Content.from(TextSegment.from("bad1", Metadata.from("relevance_grade", "INCORRECT")))));

        assertThat(evaluator.calls).hasSize(1);
    }

    @Test
    void unknownGradeValue_doesNotCountAsGraded() {
        retrieve(List.of(Content.from(TextSegment.from("good1", Metadata.from("relevance_grade", "PROBABLY")))));

        assertThat(evaluator.calls).hasSize(1);
    }

    @Test
    void nonStringGradeValue_doesNotThrow() {
        retrieve(List.of(Content.from(TextSegment.from("good1", new Metadata().put("relevance_grade", 1)))));

        assertThat(evaluator.calls).hasSize(1);
    }
}
