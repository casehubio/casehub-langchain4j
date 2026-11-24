package io.casehub.langchain4j.tracking.quarkus;

import static org.assertj.core.api.Assertions.assertThat;

import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import io.casehub.langchain4j.crag.CragContentRetriever;
import io.casehub.langchain4j.tenancy.TenantIsolatingContentRetriever;
import io.casehub.langchain4j.tracking.TrackingContentRetriever;
import io.casehub.neocortex.rag.CorpusRef;
import io.casehub.neocortex.rag.RetrievedChunk;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/** Tracking(CRAG(Tenancy(app))): the record holds only what the model receives. */
@QuarkusTest
@TestProfile(TrackingWithCragAndTenancyTest.Profile.class)
class TrackingWithCragAndTenancyTest {

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Set<Class<?>> getEnabledAlternatives() {
            return Set.of(TrackingTestBeans.TestTracker.class, TrackingTestBeans.FakeEvaluator.class,
                    TrackingTestBeans.TenantA.class);
        }
    }

    @Inject
    ContentRetriever retriever;

    @Inject
    TrackingTestBeans.TestTracker tracker;

    @Test
    void trackingWrapsCragWrapsTenancy_recordsCurrentTenantsSurvivors_noOutrankingWarning() {
        List<String> warnings = new CopyOnWriteArrayList<>();
        java.util.logging.Handler handler = new java.util.logging.Handler() {
            @Override
            public void publish(java.util.logging.LogRecord record) {
                warnings.add(String.valueOf(record.getMessage()));
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        java.util.logging.Logger root = java.util.logging.Logger.getLogger("io.casehub.langchain4j");
        root.addHandler(handler);
        try {
            assertThat(retriever.retrieve(Query.from("q"))).extracting(c -> c.textSegment().text())
                    .containsExactly("good-a");
        } finally {
            root.removeHandler(handler);
        }

        assertThat(tracker.records).singleElement().satisfies(r -> {
            assertThat(r.corpus()).isEqualTo(new CorpusRef("a", "default"));
            assertThat(r.chunks()).extracting(RetrievedChunk::sourceDocumentId).containsExactly("doc-good-a");
        });
        Object outer = Unwrap.all(retriever);
        assertThat(outer).isInstanceOf(TrackingContentRetriever.class);
        Object crag = Unwrap.all(((TrackingContentRetriever) outer).delegate());
        assertThat(crag).isInstanceOf(CragContentRetriever.class);
        assertThat(Unwrap.all(((CragContentRetriever) crag).delegate())).isInstanceOf(TenantIsolatingContentRetriever.class);
        assertThat(warnings).noneMatch(m -> m.contains("outranks"));
    }
}
