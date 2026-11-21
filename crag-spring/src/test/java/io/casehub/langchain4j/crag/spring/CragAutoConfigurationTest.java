package io.casehub.langchain4j.crag.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import io.casehub.langchain4j.crag.CragContentRetriever;
import io.casehub.langchain4j.crag.CragRetrievalQuality;
import io.casehub.langchain4j.tenancy.TenantIsolatingContentRetriever;
import io.casehub.langchain4j.tenancy.spring.TenancyAutoConfiguration;
import io.casehub.neocortex.rag.RelevanceEvaluator;
import io.casehub.neocortex.rag.RelevanceGrade;
import io.casehub.neocortex.rag.ScoredGrade;
import io.casehub.platform.api.identity.CurrentPrincipal;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;

class CragAutoConfigurationTest {

    static final List<String> EVALUATED = new CopyOnWriteArrayList<>();

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CragAutoConfiguration.class));

    private static Content content(String text, String tenant) {
        return Content.from(TextSegment.from(text, Metadata.from("casehub_tenant_id", tenant)));
    }

    private static List<String> texts(List<Content> contents) {
        return contents.stream().map(c -> c.textSegment().text()).toList();
    }

    static class QualityListener {
        final List<CragRetrievalQuality> received = new CopyOnWriteArrayList<>();

        @EventListener
        void on(CragRetrievalQuality quality) {
            received.add(quality);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class AppConfig {
        @Bean
        ContentRetriever appRetriever() {
            return query -> List.of(content("good-a", "a"), content("bad-a", "a"), content("good-b", "b"));
        }

        @Bean
        QualityListener qualityListener() {
            return new QualityListener();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class EvaluatorConfig {
        @Bean
        RelevanceEvaluator relevanceEvaluator() {
            return (query, chunks) -> {
                chunks.forEach(c -> EVALUATED.add(c.content()));
                return chunks.stream().map(c -> c.content().startsWith("good")
                        ? new ScoredGrade(RelevanceGrade.CORRECT, 0.9f)
                        : new ScoredGrade(RelevanceGrade.INCORRECT, 0.1f)).toList();
            };
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class PrincipalConfig {
        @Bean
        CurrentPrincipal currentPrincipal() {
            CurrentPrincipal principal = mock(CurrentPrincipal.class);
            when(principal.tenancyId()).thenReturn("a");
            return principal;
        }
    }

    @Test
    void wrapsRetriever_dropsIncorrect_publishesEvent() {
        runner.withUserConfiguration(AppConfig.class, EvaluatorConfig.class).run(context -> {
            ContentRetriever retriever = context.getBean(ContentRetriever.class);

            assertThat(retriever).isInstanceOf(CragContentRetriever.class);
            assertThat(texts(retriever.retrieve(Query.from("q")))).containsExactly("good-a", "good-b");
            assertThat(context.getBean(QualityListener.class).received).singleElement()
                    .satisfies(q -> assertThat(q.quality().totalIncorrect()).isEqualTo(1));
        });
    }

    @Test
    void noEvaluator_leavesRetrieverUnwrapped() {
        runner.withUserConfiguration(AppConfig.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(ContentRetriever.class)).isNotInstanceOf(CragContentRetriever.class);
        });
    }

    @Test
    void alreadyCrag_notRewrapped() {
        runner.withUserConfiguration(EvaluatorConfig.class)
                .withBean("own", ContentRetriever.class,
                        () -> new CragContentRetriever(q -> List.of(), (query, chunks) -> List.of(), q -> {}))
                .run(context -> {
                    CragContentRetriever retriever = (CragContentRetriever) context.getBean(ContentRetriever.class);
                    assertThat(retriever.delegate()).isNotInstanceOf(CragContentRetriever.class);
                });
    }

    @Test
    void withTenancy_nestsCragOutside_evaluatorSeesOnlyCurrentTenant() {
        EVALUATED.clear();
        runner.withConfiguration(AutoConfigurations.of(TenancyAutoConfiguration.class))
                .withUserConfiguration(AppConfig.class, EvaluatorConfig.class, PrincipalConfig.class)
                .run(context -> {
                    ContentRetriever retriever = context.getBean(ContentRetriever.class);

                    assertThat(retriever).isInstanceOf(CragContentRetriever.class);
                    assertThat(((CragContentRetriever) retriever).delegate())
                            .isInstanceOf(TenantIsolatingContentRetriever.class);
                    assertThat(texts(retriever.retrieve(Query.from("q")))).containsExactly("good-a");
                    assertThat(EVALUATED).containsExactly("good-a", "bad-a");
                });
    }

    @Test
    void severalEvaluators_failWithClearMessage() {
        runner.withUserConfiguration(AppConfig.class, EvaluatorConfig.class)
                .withBean("secondEvaluator", RelevanceEvaluator.class,
                        () -> (query, chunks) -> chunks.stream().map(c -> new ScoredGrade(RelevanceGrade.CORRECT, 1f)).toList())
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .hasStackTraceContaining("several RelevanceEvaluator beans"));
    }
}
