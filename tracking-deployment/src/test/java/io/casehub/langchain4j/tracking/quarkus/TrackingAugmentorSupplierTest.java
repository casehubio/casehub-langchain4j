package io.casehub.langchain4j.tracking.quarkus;

import static org.assertj.core.api.Assertions.assertThat;

import dev.langchain4j.rag.RetrievalAugmentor;
import io.casehub.langchain4j.tracking.TrackingRetrievalAugmentor;
import io.quarkus.arc.Arc;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** A Supplier<RetrievalAugmentor> bean (quarkus-langchain4j's pattern) hands out a tracking augmentor. */
@QuarkusTest
@TestProfile(TrackingAugmentorSupplierTest.Profile.class)
class TrackingAugmentorSupplierTest {

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Set<Class<?>> getEnabledAlternatives() {
            return Set.of(TrackingTestBeans.TestTracker.class);
        }

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("casehub.test.augmentor", "supplier");
        }
    }

    @Inject
    TrackingTestBeans.TestTracker tracker;

    @Test
    void supplierBean_getReturnsTracking() {
        // quarkus-langchain4j's lookup for @RegisterAiService(retrievalAugmentor = AppSupplier.class)
        RetrievalAugmentor augmentor = Arc.container().instance(AugmentorTestBeans.AppSupplier.class).get().get();
        assertThat(augmentor).isInstanceOf(TrackingRetrievalAugmentor.class);

        augmentor.augment(AugmentorTestBeans.question());

        assertThat(tracker.records).singleElement()
                .satisfies(r -> assertThat(r.query().text()).isEqualTo(AugmentorTestBeans.QUESTION));
    }
}
