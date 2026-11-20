package io.casehub.langchain4j.hybridsearch.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import io.casehub.langchain4j.hybridsearch.CasehubHybridContentRetriever;
import io.casehub.neocortex.rag.CaseRetriever;
import io.casehub.neocortex.rag.CorpusRef;
import io.casehub.neocortex.rag.RetrievedChunk;
import io.casehub.platform.api.identity.CurrentPrincipal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class HybridSearchAutoConfigurationTest {

    static final List<CorpusRef> SEEN = new ArrayList<>();

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(HybridSearchAutoConfiguration.class));

    @Test
    void caseRetrieverBean_registersNamedRetriever() {
        SEEN.clear();
        runner.withUserConfiguration(NeocortexConfig.class)
                .withPropertyValues(
                        "casehub.langchain4j.hybrid-search.corpora=kb, faq",
                        "casehub.langchain4j.hybrid-search.max-results=3")
                .run(context -> {
                    ContentRetriever retriever = context.getBean("casehubHybridRetriever", ContentRetriever.class);
                    assertThat(retriever).isInstanceOf(CasehubHybridContentRetriever.class);

                    List<Content> contents = retriever.retrieve(Query.from("how?"));

                    assertThat(contents).extracting(c -> c.textSegment().text()).containsExactly("answer");
                    assertThat(SEEN).containsExactly(new CorpusRef("acme", "kb"), new CorpusRef("acme", "faq"));
                });
    }

    @Test
    void noCaseRetriever_contextStarts_andUseFailsClearly() {
        runner.withPropertyValues("casehub.langchain4j.hybrid-search.corpora=kb").run(context -> {
            assertThat(context).hasNotFailed();
            assertThatThrownBy(() -> context.getBean("casehubHybridRetriever"))
                    .hasStackTraceContaining("CaseRetriever");
        });
    }

    @Test
    void userContentRetriever_coexists() {
        ContentRetriever own = query -> List.of();
        runner.withUserConfiguration(NeocortexConfig.class)
                .withBean("ownRetriever", ContentRetriever.class, () -> own)
                .withPropertyValues("casehub.langchain4j.hybrid-search.corpora=kb")
                .run(context -> {
                    assertThat(context.getBean(ContentRetriever.class)).isSameAs(own);
                    assertThat(context.getBean("casehubHybridRetriever")).isNotSameAs(own);
                });
    }

    @Test
    void hybridRetrieverAlone_isNotInjectedUnqualified() {
        runner.withUserConfiguration(NeocortexConfig.class, UnqualifiedConsumerConfig.class)
                .withPropertyValues("casehub.langchain4j.hybrid-search.corpora=kb")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(UnqualifiedConsumer.class).retriever).isNull();
                });
    }

    @Test
    void missingCorporaProperty_failsWithPropertyName() {
        runner.withUserConfiguration(NeocortexConfig.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThatThrownBy(() -> context.getBean("casehubHybridRetriever"))
                    .hasStackTraceContaining("casehub.langchain4j.hybrid-search.corpora");
        });
    }

    record UnqualifiedConsumer(ContentRetriever retriever) {}

    @Configuration(proxyBeanMethods = false)
    static class UnqualifiedConsumerConfig {
        @Bean
        UnqualifiedConsumer unqualifiedConsumer(ObjectProvider<ContentRetriever> retriever) {
            return new UnqualifiedConsumer(retriever.getIfAvailable());
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class NeocortexConfig {
        @Bean
        CaseRetriever caseRetriever() {
            return (query, corpus, maxResults, filter) -> {
                SEEN.add(corpus);
                return SEEN.size() == 1
                        ? List.of(new RetrievedChunk("answer", "doc-1", 0.9, Map.of()))
                        : List.of();
            };
        }

        @Bean
        CurrentPrincipal currentPrincipal() {
            CurrentPrincipal principal = mock(CurrentPrincipal.class);
            when(principal.tenancyId()).thenReturn("acme");
            return principal;
        }
    }
}
