package io.casehub.langchain4j.hybridsearch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.ContentMetadata;
import dev.langchain4j.rag.query.Query;
import io.casehub.neocortex.rag.CaseContextRetriever;
import io.casehub.neocortex.rag.CaseRetriever;
import io.casehub.neocortex.rag.CorpusRef;
import io.casehub.neocortex.rag.PayloadFilter;
import io.casehub.neocortex.rag.RelevanceGrade;
import io.casehub.neocortex.rag.RetrievalQuery;
import io.casehub.neocortex.rag.RetrievedChunk;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class CasehubHybridContentRetrieverTest {

    /** Records every call and answers the same chunks for the first corpus only. */
    static class FakeCaseRetriever implements CaseRetriever {
        final List<CorpusRef> corpora = new ArrayList<>();
        final List<Integer> maxResults = new ArrayList<>();
        final List<String> queries = new ArrayList<>();
        List<RetrievedChunk> chunks = List.of();

        @Override
        public List<RetrievedChunk> retrieve(RetrievalQuery query, CorpusRef corpus, int max, PayloadFilter filter) {
            queries.add(query.text());
            maxResults.add(max);
            corpora.add(corpus);
            return corpora.size() == 1 ? chunks : List.of();
        }
    }

    private final FakeCaseRetriever fake = new FakeCaseRetriever();
    private final AtomicReference<String> tenant = new AtomicReference<>("acme");

    private CasehubHybridContentRetriever retriever(HybridSearchConfig config) {
        return new CasehubHybridContentRetriever(new CaseContextRetriever(fake), tenant::get, config);
    }

    private CasehubHybridContentRetriever retriever() {
        return retriever(new HybridSearchConfig(List.of("kb"), 10));
    }

    @Test
    void mapsChunksToContent_preservingOrderScoreAndMetadata() {
        fake.chunks = List.of(
                new RetrievedChunk("low", "doc-2", 0.4, Map.of(), RelevanceGrade.AMBIGUOUS),
                new RetrievedChunk("high", "doc-1", 0.9, Map.of("title", "Guide"), RelevanceGrade.CORRECT));

        List<Content> contents = retriever().retrieve(Query.from("how?"));

        assertThat(contents).extracting(c -> c.textSegment().text()).containsExactly("high", "low");
        Content first = contents.get(0);
        assertThat(first.metadata()).containsEntry(ContentMetadata.SCORE, 0.9);
        assertThat(first.textSegment().metadata().toMap()).containsOnly(
                Map.entry("title", "Guide"),
                Map.entry("source_document_id", "doc-1"),
                Map.entry("relevance_grade", "CORRECT"),
                Map.entry("casehub_tenant_id", "acme"));
    }

    @Test
    void queriesOneCorpusRefPerConfiguredName_withCurrentTenant() {
        retriever(new HybridSearchConfig(List.of("kb", "faq"), 10)).retrieve(Query.from("how?"));

        assertThat(fake.corpora).containsExactly(new CorpusRef("acme", "kb"), new CorpusRef("acme", "faq"));
        assertThat(fake.queries).containsOnly("how?");
    }

    @Test
    void tenantIsReadPerQuery() {
        CasehubHybridContentRetriever retriever = retriever();
        retriever.retrieve(Query.from("q"));
        tenant.set("globex");
        retriever.retrieve(Query.from("q"));

        assertThat(fake.corpora).containsExactly(new CorpusRef("acme", "kb"), new CorpusRef("globex", "kb"));
    }

    @Test
    void passesMaxResults() {
        retriever(new HybridSearchConfig(List.of("kb"), 3)).retrieve(Query.from("how?"));

        assertThat(fake.maxResults).containsExactly(3);
    }

    @Test
    void blankContentChunk_isSkipped() {
        fake.chunks = List.of(
                new RetrievedChunk("   ", "doc-1", 0.9, Map.of()),
                new RetrievedChunk("real", "doc-2", 0.5, Map.of()));

        assertThat(retriever().retrieve(Query.from("how?")))
                .extracting(c -> c.textSegment().text()).containsExactly("real");
    }

    @Test
    void missingTenant_throwsIllegalState() {
        for (String missing : new String[] {null, " "}) {
            tenant.set(missing);
            assertThatThrownBy(() -> retriever().retrieve(Query.from("how?")))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("CurrentPrincipal");
        }
        assertThat(fake.corpora).isEmpty();
    }

    @Test
    void config_rejectsEmptyCorpora() {
        assertThatThrownBy(() -> new HybridSearchConfig(List.of(), 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HybridSearchConfig(null, 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HybridSearchConfig(List.of("kb", " "), 10))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void config_rejectsZeroMaxResults() {
        assertThatThrownBy(() -> new HybridSearchConfig(List.of("kb"), 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void config_parsesCommaSeparatedCorpora() {
        assertThat(HybridSearchConfig.of(" kb, faq ,", 5))
                .isEqualTo(new HybridSearchConfig(List.of("kb", "faq"), 5));
    }
}
