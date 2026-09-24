package io.casehub.langchain4j.audit.spring;

import dev.langchain4j.model.chat.listener.ChatModelListener;
import io.casehub.langchain4j.audit.AuditEntryWriter;
import io.casehub.langchain4j.audit.CasehubChatModelListener;
import io.casehub.langchain4j.audit.LoggingAuditEntryWriter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnClass(ChatModelListener.class)
public class AuditAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public AuditEntryWriter auditEntryWriter() {
        // TODO: return LedgerAuditEntryWriter when casehub-ledger is on classpath
        return new LoggingAuditEntryWriter();
    }

    @Bean
    public CasehubChatModelListener casehubChatModelListener(AuditEntryWriter writer) {
        return new CasehubChatModelListener(writer);
    }
}
