package io.casehub.langchain4j.tracking.deployment;

import dev.langchain4j.rag.RetrievalAugmentor;
import io.casehub.langchain4j.tracking.quarkus.TrackingAugmentorCreator;
import io.casehub.langchain4j.tracking.quarkus.TrackingBeans;
import io.quarkus.arc.deployment.BeanDiscoveryFinishedBuildItem;
import io.quarkus.arc.deployment.SyntheticBeanBuildItem;
import io.quarkus.arc.processor.BeanInfo;
import io.quarkus.arc.processor.BeanResolver;
import io.quarkus.arc.processor.DotNames;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import jakarta.enterprise.inject.AmbiguousResolutionException;
import jakarta.inject.Singleton;
import java.util.Set;
import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.ClassType;

/**
 * Tracks the application's {@link RetrievalAugmentor} — the bean quarkus-langchain4j's default lookup
 * gives every {@code @RegisterAiService} — by registering a synthetic alternative around it, but only
 * when ArC resolves exactly one {@code @Default} augmentor (or EasyRAG will register its own). With
 * none, or an ambiguous set, nothing is registered, so AI services see what they would see without
 * this module.
 */
public class TrackingProcessor {

    static final String FEATURE = "casehub-langchain4j-tracking";

    /** EasyRAG registers its augmentor synthetically, from the same build item, when no other exists. */
    static final String EASY_RAG_AUGMENTOR = "io.quarkiverse.langchain4j.easyrag.runtime.EasyRetrievalAugmentor";

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    @BuildStep
    void trackApplicationAugmentor(BeanDiscoveryFinishedBuildItem beans, BuildProducer<SyntheticBeanBuildItem> synthetic) {
        BeanResolver resolver = beans.getBeanResolver();
        // An unqualified injection point means @Default; resolveBeans without qualifiers matches any.
        Set<BeanInfo> found = resolver.resolveBeans(ClassType.create(RetrievalAugmentor.class),
                AnnotationInstance.builder(DotNames.DEFAULT).build());
        boolean track = found.isEmpty() ? easyRagPresent() : resolvesToOne(resolver, found);
        if (!track) {
            return;
        }
        // Declared as the interface: without a tracker the creator returns the application's augmentor as is.
        synthetic.produce(SyntheticBeanBuildItem.configure(RetrievalAugmentor.class)
                .types(RetrievalAugmentor.class)
                .named(TrackingBeans.AUGMENTOR_BEAN_NAME)
                .scope(Singleton.class)
                .alternative(true)
                .priority(TrackingBeans.PRIORITY)
                .creator(TrackingAugmentorCreator.class)
                .unremovable()
                .done());
    }

    private static boolean resolvesToOne(BeanResolver resolver, Set<BeanInfo> found) {
        try {
            return resolver.resolveAmbiguity(found) != null;
        } catch (AmbiguousResolutionException e) {
            return false;
        }
    }

    private static boolean easyRagPresent() {
        try {
            Class.forName(EASY_RAG_AUGMENTOR, false, Thread.currentThread().getContextClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
