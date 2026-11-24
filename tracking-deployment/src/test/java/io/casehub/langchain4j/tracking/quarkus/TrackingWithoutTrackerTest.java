package io.casehub.langchain4j.tracking.quarkus;

import static org.assertj.core.api.Assertions.assertThat;

import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import io.casehub.langchain4j.crag.CragContentRetriever;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** No tracker: the application still gets CRAG(Tenancy(app)). */
@QuarkusTest
@TestProfile(TrackingWithoutTrackerTest.Profile.class)
class TrackingWithoutTrackerTest {

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Set<Class<?>> getEnabledAlternatives() {
            return Set.of(TrackingTestBeans.FakeEvaluator.class, TrackingTestBeans.TenantA.class);
        }
    }

    @Inject
    ContentRetriever retriever;

    @Test
    void noTracker_cragStillApplies() {
        assertThat(retriever.retrieve(Query.from("q"))).extracting(c -> c.textSegment().text())
                .containsExactly("good-a");
        assertThat(Unwrap.all(retriever)).isInstanceOf(CragContentRetriever.class);
    }
}
