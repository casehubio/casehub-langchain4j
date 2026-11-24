package io.casehub.langchain4j.tracking.quarkus;

import static org.assertj.core.api.Assertions.assertThat;

import dev.langchain4j.rag.RetrievalAugmentor;
import io.casehub.langchain4j.tracking.TrackingRetrievalAugmentor;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Application augmentor, no tracker: the application's augmentor is injected as is. */
@QuarkusTest
@TestProfile(TrackingAugmentorWithoutTrackerTest.Profile.class)
class TrackingAugmentorWithoutTrackerTest {

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("casehub.test.augmentor", "bean");
        }
    }

    @Inject
    RetrievalAugmentor augmentor;

    @Test
    void noTracker_augmentorUnwrapped() {
        assertThat(augmentor).isNotNull().isNotInstanceOf(TrackingRetrievalAugmentor.class);
    }
}
