package io.casehub.langchain4j.examples;

import static org.assertj.core.api.Assertions.assertThat;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.invocation.InvocationParameters;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.rag.AugmentationRequest;
import dev.langchain4j.rag.DefaultRetrievalAugmentor;
import dev.langchain4j.rag.RetrievalAugmentor;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import io.casehub.langchain4j.tracking.RecordingTracker;
import io.casehub.langchain4j.tracking.TrackingContentRetriever;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/** Keeps docs/guides/query-rewriting.md honest: each recipe records the question once and merges passages. */
class QueryRewritingExampleTest {

    static final String QUESTION = "How do I reset my password?";
    static final String HYPOTHETICAL = "Open Settings, choose Security and click Reset password.";

    final RecordingTracker tracker = new RecordingTracker();

    /** Queries the application's retriever received. */
    final List<String> searched = new CopyOnWriteArrayList<>();

    /** The application's retriever: a passage every query finds, and one per query. */
    final ContentRetriever appRetriever = query -> {
        searched.add(query.text());
        return List.of(passage("Passwords are managed under Settings > Security.", "doc-security"),
                passage("Result for: " + query.text(), "doc-" + query.text().hashCode()));
    };

    /** What casehub-langchain4j-tracking makes of the retriever bean in Spring and Quarkus. */
    final ContentRetriever retriever = new TrackingContentRetriever(appRetriever, tracker, "default", () -> "acme", e -> {});

    /** An offline model: query variants for the expanding transformer, a passage for HyDE. */
    final ChatModel model = new ChatModel() {
        @Override
        public ChatResponse doChat(ChatRequest request) {
            String prompt = ((UserMessage) request.messages().get(0)).singleText();
            String answer = prompt.startsWith("Write a short passage")
                    ? HYPOTHETICAL
                    : "reset forgotten password\nchange account password\nrecover login";
            return ChatResponse.builder().aiMessage(AiMessage.from(answer)).build();
        }
    };

    static Content passage(String text, String documentId) {
        return Content.from(TextSegment.from(text, Metadata.from(Map.of("source_document_id", documentId))));
    }

    /** The request an AI service builds for one user message. */
    static AugmentationRequest question() {
        UserMessage message = UserMessage.from(QUESTION);
        return new AugmentationRequest(message, dev.langchain4j.rag.query.Metadata.builder()
                .chatMessage(message)
                .invocationContext(InvocationContext.builder().invocationParameters(new InvocationParameters()).build())
                .build());
    }

    static List<String> texts(List<Content> contents) {
        return contents.stream().map(c -> c.textSegment().text()).toList();
    }

    @Test
    void expanding_recordsTheQuestionOnce_andMergesThePassageEveryVariantFound() {
        RetrievalAugmentor augmentor = QueryRewritingExample.tracked(
                QueryRewritingExample.expanding(model, retriever), tracker, () -> "acme");

        List<Content> contents = augmentor.augment(question()).contents();

        assertThat(searched).containsExactlyInAnyOrder(
                "reset forgotten password", "change account password", "recover login");
        assertThat(tracker.records).singleElement()
                .satisfies(r -> assertThat(r.query().text()).isEqualTo(QUESTION));
        assertThat(texts(contents)).filteredOn(t -> t.startsWith("Passwords")).hasSize(1);
        assertThat(contents).hasSize(4).allSatisfy(c ->
                assertThat(c.textSegment().metadata().getString("retrieval_id")).isEqualTo(tracker.records.get(0).id()));
    }

    @Test
    void hyde_searchesWithTheQuestionAndTheHypotheticalPassage_recordsTheQuestionOnce() {
        RetrievalAugmentor augmentor = QueryRewritingExample.tracked(
                QueryRewritingExample.hyde(model, retriever), tracker, () -> "acme");

        List<Content> contents = augmentor.augment(question()).contents();

        assertThat(searched).containsExactlyInAnyOrder(QUESTION, HYPOTHETICAL);
        assertThat(tracker.records).singleElement()
                .satisfies(r -> assertThat(r.query().text()).isEqualTo(QUESTION));
        assertThat(texts(contents)).containsExactlyInAnyOrder("Passwords are managed under Settings > Security.",
                "Result for: " + QUESTION, "Result for: " + HYPOTHETICAL);
    }

    @Test
    void transformerDroppingQueryMetadata_recordsEveryRewrittenQueryInstead() {
        RetrievalAugmentor augmentor = QueryRewritingExample.tracked(DefaultRetrievalAugmentor.builder()
                .queryTransformer(query -> List.of(Query.from("first variant"), Query.from("second variant")))
                .contentRetriever(retriever)
                .build(), tracker, () -> "acme");

        augmentor.augment(question());

        assertThat(tracker.records).extracting(r -> r.query().text())
                .containsExactlyInAnyOrder("first variant", "second variant");
    }
}
