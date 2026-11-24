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
@TestProfile(TrackingQualifiedAugmentorTest.Profile.class)
class TrackingQualifiedAugmentorTest {

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Set<Class<?>> getEnabledAlternatives() {
            return Set.of(TrackingTestBeans.TestTracker.class);
        }

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("casehub.test.augmentor", "qualified");
        }
    }

    @Inject
    Instance<RetrievalAugmentor> augmentors;

    @Inject
    TrackingTestBeans.TestTracker tracker;

    @Test
    void qualifiedOnly_defaultUnresolvable() {
        // default AI services get no augmentor, as without this module
        assertThat(augmentors.isResolvable()).isFalse();
    }
}
