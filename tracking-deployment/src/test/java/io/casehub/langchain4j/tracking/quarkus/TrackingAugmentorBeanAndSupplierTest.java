package io.casehub.langchain4j.tracking.quarkus;

import static org.assertj.core.api.Assertions.assertThat;

import dev.langchain4j.rag.RetrievalAugmentor;
import io.casehub.langchain4j.tracking.TrackingRetrievalAugmentor;
import io.quarkus.arc.Arc;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** A supplier returning the already tracked augmentor bean is not wrapped twice. */
@QuarkusTest
@TestProfile(TrackingAugmentorBeanAndSupplierTest.Profile.class)
class TrackingAugmentorBeanAndSupplierTest {

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Set<Class<?>> getEnabledAlternatives() {
            return Set.of(TrackingTestBeans.TestTracker.class);
        }

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("casehub.test.augmentor", "bean-and-supplier");
        }
    }

    @Test
    void supplierReturningTrackedBean_notWrappedTwice() {
        RetrievalAugmentor augmentor = Arc.container().instance(AugmentorTestBeans.BeanAndSupplier.class).get().get();

        assertThat(augmentor).isInstanceOf(TrackingRetrievalAugmentor.class);
        assertThat(((TrackingRetrievalAugmentor) augmentor).delegate()).isNotInstanceOf(TrackingRetrievalAugmentor.class);
    }
}
