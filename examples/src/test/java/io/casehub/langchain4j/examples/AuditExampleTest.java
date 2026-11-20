package io.casehub.langchain4j.examples;

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.langchain4j.audit.AuditEvent;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

@QuarkusTest
class AuditExampleTest {

    @Inject
    AuditExample example;

    @Inject
    RecordingAuditWriter writer;

    @Test
    void oneChatCall_producesOneCompletionEntry() {
        writer.events.clear();

        String answer = example.ask("What is the capital of France?");

        assertThat(answer).isEqualTo("echo: What is the capital of France?");
        assertThat(writer.events).hasSize(1);
        AuditEvent event = writer.events.get(0);
        assertThat(event.kind()).isEqualTo(AuditEvent.AI_CHAT_COMPLETION);
        assertThat(event.actorId()).isEqualTo("llm:echo-model");
        assertThat(event.data().values()).noneMatch(v -> String.valueOf(v).contains("France"));
    }
}
