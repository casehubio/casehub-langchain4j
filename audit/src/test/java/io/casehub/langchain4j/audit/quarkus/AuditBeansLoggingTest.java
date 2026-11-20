package io.casehub.langchain4j.audit.quarkus;

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.langchain4j.audit.AuditEntryWriter;
import io.casehub.langchain4j.audit.CasehubAgentListener;
import io.casehub.langchain4j.audit.CasehubChatModelListener;
import io.casehub.langchain4j.audit.LoggingAuditEntryWriter;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

@QuarkusTest
class AuditBeansLoggingTest {

    @Inject
    AuditEntryWriter writer;

    @Inject
    CasehubChatModelListener chatModelListener;

    @Inject
    CasehubAgentListener agentListener;

    @Test
    void noLedgerBeans_usesLoggingWriter() {
        assertThat(writer).isInstanceOf(LoggingAuditEntryWriter.class);
    }

    @Test
    void listenersAreInjectable() {
        assertThat(chatModelListener).isNotNull();
        assertThat(agentListener).isNotNull();
        assertThat(agentListener.inheritedBySubagents()).isTrue();
    }
}
