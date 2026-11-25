package io.casehub.langchain4j.tracking.quarkus;

import static org.assertj.core.api.Assertions.assertThat;

import dev.langchain4j.rag.RetrievalAugmentor;
import io.casehub.langchain4j.tracking.TrackingRetrievalAugmentor;
import io.quarkus.arc.Arc;
import io.quarkus.arc.InjectableBean;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** A {@code @Dependent} application augmentor lives and dies with the synthetic tracking bean that holds it. */
@QuarkusTest
@TestProfile(TrackingDependentAugmentorTest.Profile.class)
class TrackingDependentAugmentorTest {

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Set<Class<?>> getEnabledAlternatives() {
            return Set.of(TrackingTestBeans.TestTracker.class);
        }

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("casehub.test.augmentor", "dependent");
        }
    }

    @Inject
    Instance<RetrievalAugmentor> augmentors;

    @Test
    void destroyingTheTrackingBean_destroysTheDependentAugmentor() {
        RetrievalAugmentor augmentor = augmentors.get();
        assertThat(augmentor).isInstanceOf(TrackingRetrievalAugmentor.class);
        int before = AugmentorTestBeans.DependentAugmentor.DESTROYED.get();

        InjectableBean<?> tracking = Arc.container().namedBean(TrackingBeans.AUGMENTOR_BEAN_NAME);
        Arc.container().getActiveContext(Singleton.class).destroy(tracking);

        assertThat(AugmentorTestBeans.DependentAugmentor.DESTROYED.get()).isEqualTo(before + 1);
    }
}
