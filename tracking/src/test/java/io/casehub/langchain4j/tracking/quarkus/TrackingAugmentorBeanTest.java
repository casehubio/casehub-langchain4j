package io.casehub.langchain4j.tracking.quarkus;

import static org.assertj.core.api.Assertions.assertThat;

import dev.langchain4j.rag.RetrievalAugmentor;
import io.casehub.langchain4j.tracking.TrackingRetrievalAugmentor;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** An application RetrievalAugmentor bean resolves to the tracking one: one record per question. */
@QuarkusTest
@TestProfile(TrackingAugmentorBeanTest.Profile.class)
class TrackingAugmentorBeanTest {

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Set<Class<?>> getEnabledAlternatives() {
            return Set.of(TrackingTestBeans.TestTracker.class);
        }

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("casehub.test.augmentor", "bean");
        }
    }

    @Inject
    RetrievalAugmentor augmentor;

    @Inject
    TrackingTestBeans.TestTracker tracker;

    @Test
    void augmentorBean_resolvesToTracking() {
        assertThat(augmentor).isInstanceOf(TrackingRetrievalAugmentor.class);

        augmentor.augment(AugmentorTestBeans.question());

        assertThat(tracker.records).singleElement()
                .satisfies(r -> assertThat(r.query().text()).isEqualTo(AugmentorTestBeans.QUESTION));
    }
}
