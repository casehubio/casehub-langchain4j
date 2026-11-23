package io.casehub.langchain4j.tracking.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.invocation.InvocationParameters;
import dev.langchain4j.rag.AugmentationRequest;
import dev.langchain4j.rag.DefaultRetrievalAugmentor;
import dev.langchain4j.rag.RetrievalAugmentor;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import io.casehub.langchain4j.crag.CragContentRetriever;
import io.casehub.langchain4j.crag.spring.CragAutoConfiguration;
import io.casehub.langchain4j.tenancy.TenantIsolatingContentRetriever;
import io.casehub.langchain4j.tenancy.spring.TenancyAutoConfiguration;
import io.casehub.langchain4j.tracking.RecordingTracker;
import io.casehub.langchain4j.tracking.TrackingContentRetriever;
import io.casehub.langchain4j.tracking.TrackingRetrievalAugmentor;
import io.casehub.neocortex.rag.CorpusRef;
import io.casehub.neocortex.rag.RelevanceEvaluator;
import io.casehub.neocortex.rag.RelevanceGrade;
import io.casehub.neocortex.rag.RetrievalRecorded;
import io.casehub.neocortex.rag.RetrievedChunk;
import io.casehub.neocortex.rag.ScoredGrade;
import io.casehub.platform.api.identity.CurrentPrincipal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;

class TrackingAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TrackingAutoConfiguration.class));

    private static Content content(String text, String tenant) {
        return Content.from(TextSegment.from(text, Metadata.from(Map.of(
                "casehub_tenant_id", tenant, "source_document_id", "doc-" + text))));
    }

    private static List<String> texts(List<Content> contents) {
        return contents.stream().map(c -> c.textSegment().text()).toList();
    }

    static class RecordedListener {
        final List<RetrievalRecorded> received = new CopyOnWriteArrayList<>();

        @EventListener
        void on(RetrievalRecorded recorded) {
            received.add(recorded);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class AppConfig {
        @Bean
        ContentRetriever appRetriever() {
            return query -> List.of(content("good-a", "a"), content("bad-a", "a"), content("good-b", "b"));
        }

        @Bean
        RecordedListener recordedListener() {
            return new RecordedListener();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class TrackerConfig {
        @Bean
        RecordingTracker retrievalTracker() {
            return new RecordingTracker();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class EvaluatorConfig {
        @Bean
        RelevanceEvaluator relevanceEvaluator() {
            return (query, chunks) -> chunks.stream().map(c -> c.content().startsWith("good")
                    ? new ScoredGrade(RelevanceGrade.CORRECT, 0.9f)
                    : new ScoredGrade(RelevanceGrade.INCORRECT, 0.1f)).toList();
        }
    }

    private static CurrentPrincipal principal(String tenancyId) {
        CurrentPrincipal principal = mock(CurrentPrincipal.class);
        when(principal.tenancyId()).thenReturn(tenancyId);
        return principal;
    }

    @Test
    void wrapsRetriever_records_publishesEvent() {
        runner.withUserConfiguration(AppConfig.class, TrackerConfig.class).run(context -> {
            ContentRetriever retriever = context.getBean(ContentRetriever.class);

            assertThat(retriever).isInstanceOf(TrackingContentRetriever.class);
            assertThat(retriever.retrieve(Query.from("q"))).hasSize(3);
            assertThat(context.getBean(RecordingTracker.class).records).singleElement()
                    .satisfies(r -> assertThat(r.corpus()).isEqualTo(new CorpusRef("default", "default")));
            assertThat(context.getBean(RecordedListener.class).received).hasSize(1);
        });
    }

    @Test
    void corpusProperty_isUsed() {
        runner.withUserConfiguration(AppConfig.class, TrackerConfig.class)
                .withPropertyValues("casehub.langchain4j.tracking.corpus=kb")
                .run(context -> {
                    context.getBean(ContentRetriever.class).retrieve(Query.from("q"));

                    assertThat(context.getBean(RecordingTracker.class).records).singleElement()
                            .satisfies(r -> assertThat(r.corpus().corpusName()).isEqualTo("kb"));
                });
    }

    @Test
    void noTracker_leavesRetrieverUnwrapped() {
        runner.withUserConfiguration(AppConfig.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(ContentRetriever.class)).isNotInstanceOf(TrackingContentRetriever.class);
        });
    }

    @Test
    void severalTrackers_failsClearly() {
        runner.withUserConfiguration(AppConfig.class, TrackerConfig.class)
                .withBean("secondTracker", RecordingTracker.class, RecordingTracker::new)
                .run(context -> assertThat(context).getFailure()
                        .hasStackTraceContaining("several RetrievalTracker beans"));
    }

    @Test
    void alreadyTracking_notRewrapped() {
        runner.withUserConfiguration(TrackerConfig.class)
                .withBean("own", ContentRetriever.class, () -> new TrackingContentRetriever(q -> List.of(),
                        new RecordingTracker(), "kb", () -> "a", e -> {}))
                .run(context -> {
                    TrackingContentRetriever retriever = (TrackingContentRetriever) context.getBean(ContentRetriever.class);
                    assertThat(retriever.delegate()).isNotInstanceOf(TrackingContentRetriever.class);
                });
    }

    @Test
    void blankTenancy_notRecorded() {
        runner.withUserConfiguration(AppConfig.class, TrackerConfig.class)
                .withBean(CurrentPrincipal.class, () -> principal(" "))
                .run(context -> {
                    assertThat(context.getBean(ContentRetriever.class).retrieve(Query.from("q"))).hasSize(3);
                    assertThat(context.getBean(RecordingTracker.class).records).isEmpty();
                });
    }

    @Test
    void hybridBean_notWrapped() {
        ContentRetriever hybrid = query -> List.of();
        runner.withUserConfiguration(TrackerConfig.class)
                .withBean("casehubHybridRetriever", ContentRetriever.class, () -> hybrid)
                .run(context -> assertThat(context.getBean("casehubHybridRetriever")).isSameAs(hybrid));
    }

    @Test
    void withCragAndTenancy_nestsTrackingOutside() {
        runner.withConfiguration(AutoConfigurations.of(TenancyAutoConfiguration.class, CragAutoConfiguration.class))
                .withUserConfiguration(AppConfig.class, TrackerConfig.class, EvaluatorConfig.class)
                .withBean(CurrentPrincipal.class, () -> principal("a"))
                .run(context -> {
                    ContentRetriever retriever = context.getBean(ContentRetriever.class);

                    assertThat(retriever).isInstanceOf(TrackingContentRetriever.class);
                    ContentRetriever crag = ((TrackingContentRetriever) retriever).delegate();
                    assertThat(crag).isInstanceOf(CragContentRetriever.class);
                    assertThat(((CragContentRetriever) crag).delegate()).isInstanceOf(TenantIsolatingContentRetriever.class);

                    assertThat(texts(retriever.retrieve(Query.from("q")))).containsExactly("good-a");
                    assertThat(context.getBean(RecordingTracker.class).records).singleElement().satisfies(r -> {
                        assertThat(r.corpus().tenantId()).isEqualTo("a");
                        assertThat(r.chunks()).extracting(RetrievedChunk::sourceDocumentId).containsExactly("doc-good-a");
                    });
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class AugmentorConfig {
        @Bean
        RetrievalAugmentor appAugmentor(ContentRetriever appRetriever) {
            return DefaultRetrievalAugmentor.builder()
                    .queryTransformer(q -> List.of(Query.from("variant one", q.metadata()), Query.from("variant two", q.metadata())))
                    .contentRetriever(appRetriever)
                    .executor(Runnable::run)
                    .build();
        }
    }

    private static AugmentationRequest question() {
        UserMessage message = UserMessage.from("how do I reset my password?");
        return new AugmentationRequest(message, dev.langchain4j.rag.query.Metadata.builder().chatMessage(message)
                .invocationContext(InvocationContext.builder().invocationParameters(new InvocationParameters()).build())
                .build());
    }

    @Test
    void retrievalAugmentorBean_isWrapped() {
        runner.withUserConfiguration(AppConfig.class, TrackerConfig.class, AugmentorConfig.class).run(context -> {
            RetrievalAugmentor augmentor = context.getBean(RetrievalAugmentor.class);
            assertThat(augmentor).isInstanceOf(TrackingRetrievalAugmentor.class);

            augmentor.augment(question());

            RecordingTracker tracker = context.getBean(RecordingTracker.class);
            assertThat(tracker.records).singleElement()
                    .satisfies(r -> assertThat(r.query().text()).isEqualTo("how do I reset my password?"));
            assertThat(context.getBean(RecordedListener.class).received).hasSize(1);
        });
    }

    @Test
    void noTracker_augmentorNotWrapped() {
        runner.withUserConfiguration(AppConfig.class, AugmentorConfig.class).run(context ->
                assertThat(context.getBean(RetrievalAugmentor.class)).isNotInstanceOf(TrackingRetrievalAugmentor.class));
    }

    @Test
    void trackingAugmentorBean_notWrappedAgain() {
        runner.withUserConfiguration(AppConfig.class, TrackerConfig.class)
                .withBean("appAugmentor", RetrievalAugmentor.class,
                        () -> new TrackingRetrievalAugmentor(r -> null, new RecordingTracker(), "kb", () -> "a", e -> {}))
                .run(context -> assertThat(((TrackingRetrievalAugmentor) context.getBean(RetrievalAugmentor.class)).delegate())
                        .isNotInstanceOf(TrackingRetrievalAugmentor.class));
    }
}
