package io.casehub.langchain4j.tracking.quarkus;

import static org.assertj.core.api.Assertions.assertThat;

import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import io.casehub.langchain4j.tracking.TrackingContentRetriever;
import io.casehub.neocortex.rag.CorpusRef;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Tracker only, no principal: recorded under tenant "default", corpus from the property. */
@QuarkusTest
@TestProfile(TrackingBeansTest.Profile.class)
class TrackingBeansTest {

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Set<Class<?>> getEnabledAlternatives() {
            return Set.of(TrackingTestBeans.TestTracker.class);
        }

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("casehub.langchain4j.tracking.corpus", "kb");
        }
    }

    @Inject
    ContentRetriever retriever;

    @Inject
    TrackingTestBeans.TestTracker tracker;

    @Test
    void injectedRetriever_recordsUnderDefaultTenant_stampsAndPublishes() {
        TrackingTestBeans.RECORDED.clear();

        assertThat(Unwrap.all(retriever)).isInstanceOf(TrackingContentRetriever.class);
        List<Content> result = retriever.retrieve(Query.from("q"));

        assertThat(result).hasSize(3);
        assertThat(result).allSatisfy(c -> assertThat(c.textSegment().metadata().getString("retrieval_id")).isNotNull());
        assertThat(tracker.records).singleElement()
                .satisfies(r -> assertThat(r.corpus()).isEqualTo(new CorpusRef("default", "kb")));
        assertThat(TrackingTestBeans.RECORDED).hasSize(1);
    }
}
