package io.casehub.langchain4j.tracking.quarkus;

import static org.assertj.core.api.Assertions.assertThat;

import dev.langchain4j.rag.RetrievalAugmentor;
import io.casehub.langchain4j.tracking.TrackingRetrievalAugmentor;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(TrackingTwoAugmentorsTest.Profile.class)
class TrackingTwoAugmentorsTest {

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Set<Class<?>> getEnabledAlternatives() {
            return Set.of(TrackingTestBeans.TestTracker.class);
        }

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("casehub.test.augmentor", "two");
        }
    }

    @Inject
    Instance<RetrievalAugmentor> augmentors;

    @Inject
    TrackingTestBeans.TestTracker tracker;

    @Test
    void twoDefaults_appStarts_unresolvable() {
        // ArC itself reports ambiguity; quarkus-langchain4j then gives default AI services none
        assertThat(augmentors.isResolvable()).isFalse();
    }
}
