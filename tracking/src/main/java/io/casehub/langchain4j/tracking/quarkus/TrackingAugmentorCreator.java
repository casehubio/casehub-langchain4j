package io.casehub.langchain4j.tracking.quarkus;

import dev.langchain4j.rag.RetrievalAugmentor;
import io.quarkus.arc.Arc;
import io.quarkus.arc.BeanCreator;
import io.quarkus.arc.SyntheticCreationalContext;

/** Creates the synthetic tracking augmentor the deployment module registers. */
public class TrackingAugmentorCreator implements BeanCreator<RetrievalAugmentor> {

    @Override
    public RetrievalAugmentor create(SyntheticCreationalContext<RetrievalAugmentor> context) {
        return Arc.container().instance(TrackingBeans.class).get().trackedApplicationAugmentor(context);
    }
}
