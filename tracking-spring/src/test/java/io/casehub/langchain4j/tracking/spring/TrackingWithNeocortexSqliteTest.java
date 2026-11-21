package io.casehub.langchain4j.tracking.spring;

import static org.assertj.core.api.Assertions.assertThat;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import io.casehub.langchain4j.tracking.TrackingContentRetriever;
import io.casehub.neocortex.rag.CorpusRef;
import io.casehub.neocortex.rag.RetrievalTracker;
import io.casehub.neocortex.rag.tracking.SqliteRetrievalTracker;
import io.casehub.neocortex.rag.tracking.spring.RagTrackingAutoConfiguration;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** The application declares no tracker: neocortex's rag-tracking-spring supplies the SQLite one. */
class TrackingWithNeocortexSqliteTest {

    private static final CorpusRef CORPUS = new CorpusRef("default", "default");

    @TempDir
    Path dir;

    private ApplicationContextRunner runner(ContentRetriever app) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(RagTrackingAutoConfiguration.class,
                        TrackingAutoConfiguration.class))
                .withBean("appRetriever", ContentRetriever.class, () -> app)
                .withPropertyValues("casehub.rag.tracking.sqlite.path=" + dir.resolve("tracking.db"));
    }

    private static Content content(String text, Map<String, Object> metadata) {
        return Content.from(TextSegment.from(text, Metadata.from(metadata)));
    }

    @Test
    void sqliteTrackerFromNeocortex_recordsLangChain4jRetrievals() {
        runner(query -> List.of(content("a", Map.of("source_document_id", "doc-a")))).run(context -> {
            assertThat(context.getBean(RetrievalTracker.class)).isInstanceOf(SqliteRetrievalTracker.class);
            ContentRetriever retriever = context.getBean(ContentRetriever.class);
            assertThat(retriever).isInstanceOf(TrackingContentRetriever.class);

            List<Content> contents = retriever.retrieve(Query.from("q"));

            assertThat(context.getBean(RetrievalTracker.class).findRecords(CORPUS, Instant.EPOCH, Instant.MAX))
                    .singleElement()
                    .satisfies(r -> assertThat(contents.get(0).textSegment().metadata()
                            .getString(TrackingContentRetriever.RETRIEVAL_ID_KEY)).isEqualTo(r.retrievalId()));
        });
    }

    @Test
    void hybridStamped_notRecordedTwice() {
        runner(query -> List.of(content("a", Map.of("source_document_id", "doc-a", "_trackingId", "upstream-1"))))
                .run(context -> {
                    List<Content> contents = context.getBean(ContentRetriever.class).retrieve(Query.from("q"));

                    assertThat(context.getBean(RetrievalTracker.class)
                            .findRecords(CORPUS, Instant.EPOCH, Instant.MAX)).isEmpty();
                    assertThat(contents.get(0).textSegment().metadata()
                            .getString(TrackingContentRetriever.RETRIEVAL_ID_KEY)).isEqualTo("upstream-1");
                });
    }
}
