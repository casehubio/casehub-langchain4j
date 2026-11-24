package io.casehub.langchain4j.tracking.quarkus;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import io.casehub.langchain4j.tracking.RecordingTracker;
import io.casehub.neocortex.rag.RelevanceEvaluator;
import io.casehub.neocortex.rag.RelevanceGrade;
import io.casehub.neocortex.rag.RetrievalRecorded;
import io.casehub.neocortex.rag.RetrievedChunk;
import io.casehub.neocortex.rag.ScoredGrade;
import io.casehub.platform.api.identity.CurrentPrincipal;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Alternative;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

public final class TrackingTestBeans {

    private TrackingTestBeans() {}

    public static final List<RetrievalRecorded> RECORDED = new CopyOnWriteArrayList<>();

    private static Content content(String text, String tenant) {
        return Content.from(TextSegment.from(text, Metadata.from(Map.of(
                "casehub_tenant_id", tenant, "source_document_id", "doc-" + text))));
    }

    @ApplicationScoped
    public static class AppRetriever {
        @Produces
        @Singleton
        ContentRetriever appRetriever() {
            return query -> List.of(content("good-a", "a"), content("bad-a", "a"), content("good-b", "b"));
        }
    }

    @Alternative
    @Singleton
    public static class TestTracker extends RecordingTracker {}

    /** A second tracker, to make resolution ambiguous. */
    @Alternative
    @Singleton
    public static class SecondTracker extends RecordingTracker {}

    @Alternative
    @Singleton
    public static class FakeEvaluator implements RelevanceEvaluator {
        @Override
        public List<ScoredGrade> evaluateChunks(String query, List<RetrievedChunk> chunks) {
            return chunks.stream().map(c -> c.content().startsWith("good")
                    ? new ScoredGrade(RelevanceGrade.CORRECT, 0.9f)
                    : new ScoredGrade(RelevanceGrade.INCORRECT, 0.1f)).toList();
        }
    }

    @Alternative
    @Singleton
    public static class TenantA implements CurrentPrincipal {
        @Override public String actorId() { return "tester"; }
        @Override public Set<String> groups() { return Set.of(); }
        @Override public String tenancyId() { return "a"; }
        @Override public boolean isCrossTenantAdmin() { return false; }
    }

    @Alternative
    @Singleton
    public static class BlankTenant implements CurrentPrincipal {
        @Override public String actorId() { return "tester"; }
        @Override public Set<String> groups() { return Set.of(); }
        @Override public String tenancyId() { return " "; }
        @Override public boolean isCrossTenantAdmin() { return false; }
    }

    @ApplicationScoped
    public static class RecordedObserver {
        void onRecorded(@Observes RetrievalRecorded recorded) {
            RECORDED.add(recorded);
        }
    }
}
