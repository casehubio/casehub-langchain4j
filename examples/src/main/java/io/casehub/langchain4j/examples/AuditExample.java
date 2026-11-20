package io.casehub.langchain4j.examples;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import io.casehub.langchain4j.audit.CasehubChatModelListener;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;

/**
 * Audit: every LLM call produces an audit entry.
 *
 * <p>With {@code casehub-langchain4j-audit} on the classpath, {@link CasehubChatModelListener} is a
 * CDI bean. quarkus-langchain4j attaches every {@code ChatModelListener} bean to the models it
 * builds, so there the audit is automatic. This example builds its own offline model, so it
 * attaches the listener by hand — the one line in {@link EchoModel#listeners()}.
 *
 * <p>Entries go to the CaseHub ledger when it is present, and to the log otherwise.
 */
@ApplicationScoped
public class AuditExample {

    @Inject
    CasehubChatModelListener auditListener;

    public String ask(String question) {
        ChatModel model = new EchoModel(auditListener);
        return model.chat(ChatRequest.builder()
                        .modelName("echo-model")
                        .messages(UserMessage.from(question))
                        .build())
                .aiMessage()
                .text();
    }

    /** Stands in for a real provider model; answers offline. */
    static final class EchoModel implements ChatModel {

        private final ChatModelListener listener;

        EchoModel(ChatModelListener listener) {
            this.listener = listener;
        }

        @Override
        public List<ChatModelListener> listeners() {
            return List.of(listener);
        }

        @Override
        public ChatResponse doChat(ChatRequest request) {
            String question = ((UserMessage) request.messages().get(0)).singleText();
            return ChatResponse.builder()
                    .aiMessage(AiMessage.from("echo: " + question))
                    .tokenUsage(new TokenUsage(question.length(), question.length() + 6))
                    .build();
        }
    }
}
