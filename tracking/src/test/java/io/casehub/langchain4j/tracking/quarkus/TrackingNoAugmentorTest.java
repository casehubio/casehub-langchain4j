package io.casehub.langchain4j.tracking.quarkus;

import static org.assertj.core.api.Assertions.assertThat;

import dev.langchain4j.rag.RetrievalAugmentor;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** No application augmentor: quarkus-langchain4j's lookup yields null, retriever tracking unchanged. */
@QuarkusTest
@TestProfile(TrackingNoAugmentorTest.Profile.class)
class TrackingNoAugmentorTest {

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Set<Class<?>> getEnabledAlternatives() {
            return Set.of(TrackingTestBeans.TestTracker.class);
        }
    }

    @Inject
    Instance<RetrievalAugmentor> augmentors;

    @Inject
    ContentRetriever retriever;

    @Inject
    TrackingTestBeans.TestTracker tracker;

    @Test
    void noAugmentorBean_nullAndAppStarts() {
        // quarkus-langchain4j's BeanIfExistsRetrievalAugmentorSupplier: isResolvable() then get()
        assertThat(augmentors.isResolvable()).isTrue();
        assertThat(augmentors.get()).isNull();

        retriever.retrieve(Query.from("q"));
        assertThat(tracker.records).hasSize(1);
    }
}
