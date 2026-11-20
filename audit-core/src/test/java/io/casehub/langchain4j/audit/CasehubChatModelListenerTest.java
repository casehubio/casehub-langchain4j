package io.casehub.langchain4j.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CasehubChatModelListenerTest {

    private final RecordingAuditEntryWriter writer = new RecordingAuditEntryWriter();
    private final CasehubChatModelListener listener = new CasehubChatModelListener(writer);
    private final Map<Object, Object> attributes = new HashMap<>();
    private final ChatRequest request = ChatRequest.builder()
            .modelName("gpt-test")
            .messages(UserMessage.from("SECRET"))
            .build();

    private ChatResponse response(TokenUsage usage) {
        return ChatResponse.builder()
                .aiMessage(AiMessage.from("SECRET answer"))
                .tokenUsage(usage)
                .finishReason(FinishReason.STOP)
                .build();
    }

    private void complete(TokenUsage usage) {
        listener.onRequest(new ChatModelRequestContext(request, ModelProvider.OTHER, attributes));
        listener.onResponse(new ChatModelResponseContext(response(usage), request, ModelProvider.OTHER, attributes));
    }

    @Test
    void onResponse_writesCompletionEvent() {
        complete(new TokenUsage(10, 5));

        assertThat(writer.events).hasSize(1);
        AuditEvent event = writer.events.get(0);
        assertThat(event.kind()).isEqualTo("AI_CHAT_COMPLETION");
        assertThat(event.actorId()).isEqualTo("llm:gpt-test");
        assertThat(event.causedByEntryId()).isNull();
        assertThat(event.data()).containsOnlyKeys(
                "model", "inputTokens", "outputTokens", "totalTokens", "latencyMs", "finishReason");
        assertThat(event.data())
                .containsEntry("model", "gpt-test")
                .containsEntry("inputTokens", 10)
                .containsEntry("outputTokens", 5)
                .containsEntry("totalTokens", 15)
                .containsEntry("finishReason", "STOP");
        assertThat((Long) event.data().get("latencyMs")).isGreaterThanOrEqualTo(0L);
    }

    @Test
    void onResponse_nullTokenUsage_omitsTokenKeys() {
        complete(null);

        assertThat(writer.events.get(0).data())
                .containsOnlyKeys("model", "latencyMs", "finishReason");
    }

    @Test
    void onResponse_usesSubjectIdFromOnRequest() {
        complete(new TokenUsage(1, 1));

        Object stored = attributes.get("casehub.audit.subjectId");
        assertThat(stored).isInstanceOf(UUID.class);
        assertThat(writer.events.get(0).subjectId()).isEqualTo(stored);
    }

    @Test
    void onError_writesErrorClassOnly_becauseProviderMessagesCanQuoteTheModelText() {
        listener.onRequest(new ChatModelRequestContext(request, ModelProvider.OTHER, attributes));
        listener.onError(new ChatModelErrorContext(
                new IllegalStateException("content filter rejected: SECRET"), request, ModelProvider.OTHER, attributes));

        AuditEvent event = writer.events.get(0);
        assertThat(event.kind()).isEqualTo("AI_CHAT_ERROR");
        assertThat(event.data()).containsOnly(
                Map.entry("model", "gpt-test"),
                Map.entry("errorClass", "java.lang.IllegalStateException"));
    }

    @Test
    void writerThrows_listenerDoesNotPropagate() {
        writer.failure = new RuntimeException("ledger down");

        assertThatCode(() -> complete(new TokenUsage(1, 1))).doesNotThrowAnyException();
        assertThatCode(() -> listener.onError(new ChatModelErrorContext(
                new RuntimeException("x"), request, ModelProvider.OTHER, attributes)))
                .doesNotThrowAnyException();
    }

    @Test
    void neverRecordsMessageText() {
        complete(new TokenUsage(1, 1));
        listener.onError(new ChatModelErrorContext(
                new RuntimeException("could not parse: SECRET answer"), request, ModelProvider.OTHER, attributes));

        assertThat(writer.events).allSatisfy(event -> {
            assertThat(event.actorId()).doesNotContain("SECRET");
            assertThat(event.data().values()).noneMatch(v -> String.valueOf(v).contains("SECRET"));
        });
    }
}
