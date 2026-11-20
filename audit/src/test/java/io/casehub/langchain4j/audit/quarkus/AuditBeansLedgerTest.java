package io.casehub.langchain4j.audit.quarkus;

import static org.assertj.core.api.Assertions.assertThat;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.casehub.langchain4j.audit.AuditEntryWriter;
import io.casehub.langchain4j.audit.CasehubChatModelListener;
import io.casehub.langchain4j.audit.LedgerAuditEntryWriter;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(AuditBeansLedgerTest.LedgerProfile.class)
class AuditBeansLedgerTest {

    public static class LedgerProfile implements QuarkusTestProfile {
        @Override
        public Set<Class<?>> getEnabledAlternatives() {
            return Set.of(TestLedgerAppender.class, TestPrincipal.class);
        }
    }

    @Inject
    AuditEntryWriter writer;

    @Inject
    CasehubChatModelListener listener;

    @Inject
    TestLedgerAppender appender;

    @Test
    void ledgerAndPrincipalBeans_usesLedgerWriter() {
        assertThat(writer).isInstanceOf(LedgerAuditEntryWriter.class);
    }

    @Test
    void chatCall_appendsToLedgerWithCurrentTenant() {
        ChatRequest request = ChatRequest.builder().modelName("gpt-test").messages(UserMessage.from("hi")).build();
        ChatResponse response = ChatResponse.builder().aiMessage(AiMessage.from("hello")).build();
        Map<Object, Object> attributes = new HashMap<>();

        listener.onRequest(new ChatModelRequestContext(request, ModelProvider.OTHER, attributes));
        listener.onResponse(new ChatModelResponseContext(response, request, ModelProvider.OTHER, attributes));

        assertThat(appender.tenancies).contains("acme");
        assertThat(appender.records).anySatisfy(record ->
                assertThat(record.domainData()).containsEntry("casehub.lc4j.kind", "AI_CHAT_COMPLETION"));
    }
}
