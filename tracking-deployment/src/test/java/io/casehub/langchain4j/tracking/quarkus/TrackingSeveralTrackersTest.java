package io.casehub.langchain4j.tracking.quarkus;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.Set;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(TrackingSeveralTrackersTest.Profile.class)
class TrackingSeveralTrackersTest {

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Set<Class<?>> getEnabledAlternatives() {
            return Set.of(TrackingTestBeans.TestTracker.class, TrackingTestBeans.SecondTracker.class);
        }
    }

    @Inject
    ContentRetriever retriever;

    @Test
    void severalTrackers_failWithClearMessage() {
        assertThatThrownBy(() -> retriever.retrieve(Query.from("q")))
                .hasStackTraceContaining("several RetrievalTracker beans");
    }
}
