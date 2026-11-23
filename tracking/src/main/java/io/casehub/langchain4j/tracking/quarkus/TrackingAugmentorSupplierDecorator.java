package io.casehub.langchain4j.tracking.quarkus;

import dev.langchain4j.rag.RetrievalAugmentor;
import jakarta.annotation.Priority;
import jakarta.decorator.Decorator;
import jakarta.decorator.Delegate;
import jakarta.enterprise.inject.Any;
import jakarta.inject.Inject;
import java.util.function.Supplier;

/**
 * Tracks the augmentor a {@code Supplier<RetrievalAugmentor>} bean hands out — quarkus-langchain4j's
 * {@code @RegisterAiService(retrievalAugmentor = MySupplier.class)} pattern, when the supplier is a
 * bean. A supplier class without a scope is created outside CDI and is not covered: wrap its result
 * in a {@code TrackingRetrievalAugmentor} by hand.
 */
@Decorator
@Priority(TrackingBeans.PRIORITY)
public abstract class TrackingAugmentorSupplierDecorator implements Supplier<RetrievalAugmentor> {

    @Inject
    @Delegate
    @Any
    Supplier<RetrievalAugmentor> delegate;

    @Inject
    TrackingBeans beans;

    @Override
    public RetrievalAugmentor get() {
        RetrievalAugmentor augmentor = delegate.get();
        return augmentor == null ? null : beans.track(augmentor);
    }
}
