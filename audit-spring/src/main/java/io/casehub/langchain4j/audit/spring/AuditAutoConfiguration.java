package io.casehub.langchain4j.audit.spring;

import dev.langchain4j.agentic.observability.AgentListener;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import io.casehub.langchain4j.audit.AuditEntryWriter;
import io.casehub.langchain4j.audit.CasehubAgentListener;
import io.casehub.langchain4j.audit.CasehubChatModelListener;
import io.casehub.langchain4j.audit.LedgerAuditEntryWriter;
import io.casehub.langchain4j.audit.LoggingAuditEntryWriter;
import io.casehub.ledger.api.spi.LedgerAppender;
import io.casehub.platform.api.identity.CurrentPrincipal;
import org.jboss.logging.Logger;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.ClassUtils;

/**
 * Activates CaseHub audit for LangChain4j when this module is on the classpath.
 *
 * <p>The writer is ledger-backed when both a {@code LedgerAppender} and a {@code CurrentPrincipal}
 * bean exist, and logs otherwise. Every bean backs off to a user-defined bean of the same type.
 */
@AutoConfiguration
@ConditionalOnClass(ChatModelListener.class)
public class AuditAutoConfiguration {

    private static final Logger LOG = Logger.getLogger(AuditAutoConfiguration.class);
    private static final String LEDGER_APPENDER = "io.casehub.ledger.api.spi.LedgerAppender";
    private static final String CURRENT_PRINCIPAL = "io.casehub.platform.api.identity.CurrentPrincipal";

    @Bean
    @ConditionalOnMissingBean
    public AuditEntryWriter auditEntryWriter(BeanFactory beanFactory) {
        AuditEntryWriter logging = new LoggingAuditEntryWriter();
        ClassLoader classLoader = AuditAutoConfiguration.class.getClassLoader();
        if (ClassUtils.isPresent(LEDGER_APPENDER, classLoader)
                && ClassUtils.isPresent(CURRENT_PRINCIPAL, classLoader)) {
            AuditEntryWriter ledger = LedgerSupport.create(beanFactory, logging);
            if (ledger != null) {
                LOG.info("CaseHub audit: writing LangChain4j audit entries to the ledger");
                return ledger;
            }
        }
        LOG.info("CaseHub audit: no ledger available — LangChain4j audit entries are logged only");
        return logging;
    }

    @Bean
    @ConditionalOnMissingBean
    public CasehubChatModelListener casehubChatModelListener(AuditEntryWriter writer) {
        return new CasehubChatModelListener(writer);
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(AgentListener.class)
    static class AgentListenerConfiguration {

        @Bean
        @ConditionalOnMissingBean
        CasehubAgentListener casehubAgentListener(AuditEntryWriter writer) {
            return new CasehubAgentListener(writer);
        }
    }

    /** Touches the optional ledger and identity types; loaded only when both are present. */
    private static final class LedgerSupport {

        static AuditEntryWriter create(BeanFactory beanFactory, AuditEntryWriter fallback) {
            LedgerAppender appender = beanFactory.getBeanProvider(LedgerAppender.class).getIfAvailable();
            CurrentPrincipal principal = beanFactory.getBeanProvider(CurrentPrincipal.class).getIfAvailable();
            if (appender == null || principal == null) {
                return null;
            }
            return new LedgerAuditEntryWriter(appender, principal::tenancyId, fallback);
        }
    }
}
