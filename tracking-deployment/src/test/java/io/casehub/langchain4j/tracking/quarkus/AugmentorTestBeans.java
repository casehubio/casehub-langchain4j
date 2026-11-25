package io.casehub.langchain4j.tracking.quarkus;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.invocation.InvocationParameters;
import dev.langchain4j.rag.AugmentationRequest;
import dev.langchain4j.rag.AugmentationResult;
import dev.langchain4j.rag.DefaultRetrievalAugmentor;
import dev.langchain4j.rag.RetrievalAugmentor;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Metadata;
import dev.langchain4j.rag.query.Query;
import io.quarkus.arc.properties.IfBuildProperty;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import io.smallrye.common.annotation.Identifier;
import jakarta.enterprise.inject.Default;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/** Application augmentors, each switched on by a test profile's {@code casehub.test.augmentor}. */
public final class AugmentorTestBeans {

    private AugmentorTestBeans() {}

    static final String QUESTION = "how do I reset my password?";

    static RetrievalAugmentor rewriting(ContentRetriever retriever) {
        return DefaultRetrievalAugmentor.builder()
                .queryTransformer(q -> List.of(Query.from("variant one", q.metadata()), Query.from("variant two", q.metadata())))
                .contentRetriever(retriever)
                .executor(Runnable::run)
                .build();
    }

    static AugmentationRequest question() {
        UserMessage message = UserMessage.from(QUESTION);
        return new AugmentationRequest(message, Metadata.builder().chatMessage(message)
                .invocationContext(InvocationContext.builder().invocationParameters(new InvocationParameters()).build())
                .build());
    }

    @ApplicationScoped
    @IfBuildProperty(name = "casehub.test.augmentor", stringValue = "bean")
    public static class AppAugmentor {
        @Produces
        @Singleton
        RetrievalAugmentor appAugmentor(ContentRetriever retriever) {
            return rewriting(retriever);
        }
    }

    @ApplicationScoped
    @IfBuildProperty(name = "casehub.test.augmentor", stringValue = "supplier")
    public static class AppSupplier implements Supplier<RetrievalAugmentor> {
        @Inject
        ContentRetriever retriever;

        @Override
        public RetrievalAugmentor get() {
            return rewriting(retriever);
        }
    }

    @ApplicationScoped
    @IfBuildProperty(name = "casehub.test.augmentor", stringValue = "bean-and-supplier")
    public static class AugmentorForSupplier {
        @Produces
        @Singleton
        RetrievalAugmentor appAugmentor(ContentRetriever retriever) {
            return rewriting(retriever);
        }
    }

    /** A supplier handing out the injected (already tracked) augmentor bean. */
    @ApplicationScoped
    @IfBuildProperty(name = "casehub.test.augmentor", stringValue = "bean-and-supplier")
    public static class BeanAndSupplier implements Supplier<RetrievalAugmentor> {
        @Inject
        RetrievalAugmentor augmentor;

        @Override
        public RetrievalAugmentor get() {
            return augmentor;
        }
    }

    @ApplicationScoped
    @IfBuildProperty(name = "casehub.test.augmentor", stringValue = "class-bean")
    public static class AppClassAugmentor implements RetrievalAugmentor {
        @Inject
        ContentRetriever retriever;

        @Override
        public AugmentationResult augment(AugmentationRequest request) {
            return rewriting(retriever).augment(request);
        }
    }

    /** A {@code @Dependent} augmentor counting its {@code @PreDestroy} calls. */
    @Dependent
    @IfBuildProperty(name = "casehub.test.augmentor", stringValue = "dependent")
    public static class DependentAugmentor implements RetrievalAugmentor {
        static final AtomicInteger DESTROYED = new AtomicInteger();

        @Inject
        ContentRetriever retriever;

        @Override
        public AugmentationResult augment(AugmentationRequest request) {
            return rewriting(retriever).augment(request);
        }

        @PreDestroy
        void destroy() {
            DESTROYED.incrementAndGet();
        }
    }

    @ApplicationScoped
    @IfBuildProperty(name = "casehub.test.augmentor", stringValue = "qualified")
    public static class QualifiedAugmentor {
        @Produces
        @Singleton
        @Identifier("a")
        RetrievalAugmentor qualified(ContentRetriever retriever) {
            return rewriting(retriever);
        }
    }

    @ApplicationScoped
    @IfBuildProperty(name = "casehub.test.augmentor", stringValue = "two")
    public static class TwoAugmentors {
        @Produces
        @Singleton
        RetrievalAugmentor first(ContentRetriever retriever) {
            return rewriting(retriever);
        }

        @Produces
        @Singleton
        @Default
        @Identifier("second")
        RetrievalAugmentor second(ContentRetriever retriever) {
            return rewriting(retriever);
        }
    }
}
