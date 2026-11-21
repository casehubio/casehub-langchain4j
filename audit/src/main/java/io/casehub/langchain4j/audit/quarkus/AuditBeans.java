package io.casehub.langchain4j.audit.quarkus;

import io.casehub.langchain4j.audit.AuditEntryWriter;
import io.casehub.langchain4j.audit.CasehubAgentListener;
import io.casehub.langchain4j.audit.CasehubChatModelListener;
import io.casehub.langchain4j.audit.LedgerAuditEntryWriter;
import io.casehub.langchain4j.audit.LoggingAuditEntryWriter;
import io.casehub.ledger.api.spi.LedgerAppender;
import io.casehub.platform.api.identity.CurrentPrincipal;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import org.jboss.logging.Logger;

/**
 * Activates CaseHub audit for LangChain4j when this module is on the classpath.
 *
 * <p>The writer is ledger-backed when both a {@code LedgerAppender} and a {@code CurrentPrincipal}
 * bean exist, and logs otherwise. Every bean is a {@link DefaultBean}: an application bean of the
 * same type replaces it.
 */
@ApplicationScoped
public class AuditBeans {

    private static final Logger LOG = Logger.getLogger(AuditBeans.class);
    /**
     * Ledger-backed when both a {@code LedgerAppender} and a {@code CurrentPrincipal} bean exist.
     * They are injected, not looked up, so that ArC keeps class-style beans nothing else injects.
     */
    @Produces
    @DefaultBean
    @Singleton
    public AuditEntryWriter auditEntryWriter(Instance<LedgerAppender> appender, Instance<CurrentPrincipal> principal) {
        AuditEntryWriter logging = new LoggingAuditEntryWriter();
        if (appender.isResolvable() && principal.isResolvable()) {
            LOG.info("CaseHub audit: writing LangChain4j audit entries to the ledger");
            CurrentPrincipal current = principal.get();
            return new LedgerAuditEntryWriter(appender.get(), current::tenancyId, logging);
        }
        LOG.info("CaseHub audit: no ledger available — LangChain4j audit entries are logged only");
        return logging;
    }

    @Produces
    @DefaultBean
    @Singleton
    public CasehubChatModelListener chatModelListener(AuditEntryWriter writer) {
        return new CasehubChatModelListener(writer);
    }

    @Produces
    @DefaultBean
    @Singleton
    public CasehubAgentListener agentListener(AuditEntryWriter writer) {
        return new CasehubAgentListener(writer);
    }
}
