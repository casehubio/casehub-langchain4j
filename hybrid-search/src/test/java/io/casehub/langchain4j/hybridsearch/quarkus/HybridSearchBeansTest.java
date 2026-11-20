package io.casehub.langchain4j.hybridsearch.quarkus;

import static org.assertj.core.api.Assertions.assertThat;

import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import io.casehub.langchain4j.hybridsearch.CasehubHybridContentRetriever;
import io.casehub.neocortex.rag.CorpusRef;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.junit.jupiter.api.Test;

@QuarkusTest
class HybridSearchBeansTest {

    @Inject
    @Named("casehubHybridRetriever")
    ContentRetriever byName;

    @Inject
    @HybridSearch
    ContentRetriever byQualifier;

    @Inject
    ContentRetriever unqualified;

    @Test
    void namedRetriever_searchesConfiguredCorporaForCurrentTenant() {
        TestNeocortex.SEEN.clear();
        TestNeocortex.MAX_RESULTS.clear();
        assertThat(byName).isInstanceOf(CasehubHybridContentRetriever.class);

        assertThat(byName.retrieve(Query.from("how?")))
                .extracting(c -> c.textSegment().text()).containsExactly("answer");

        assertThat(TestNeocortex.SEEN).containsExactly(new CorpusRef("acme", "kb"), new CorpusRef("acme", "faq"));
        assertThat(TestNeocortex.MAX_RESULTS).containsOnly(3);
    }

    @Test
    void qualifierInjection_isTheSameBean() {
        assertThat(byQualifier).isSameAs(byName);
    }

    @Test
    void userContentRetriever_coexists_andStaysTheUnqualifiedBean() {
        assertThat(unqualified).isSameAs(TestNeocortex.OWN);
    }
}
