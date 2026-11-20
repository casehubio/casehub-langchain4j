package io.casehub.langchain4j.audit.quarkus;

import io.casehub.langchain4j.audit.AuditEntryWriter;
import io.casehub.langchain4j.audit.CasehubAgentListener;
import io.casehub.langchain4j.audit.CasehubChatModelListener;
import io.casehub.langchain4j.audit.LedgerAuditEntryWriter;
import io.casehub.langchain4j.audit.LoggingAuditEntryWriter;
import io.casehub.ledger.api.spi.LedgerAppender;
import io.casehub.platform.api.identity.CurrentPrincipal;
import io.quarkus.arc.Arc;
import io.quarkus.arc.DefaultBean;
import io.quarkus.arc.InstanceHandle;
import jakarta.enterprise.context.ApplicationScoped;
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
    private static final String LEDGER_APPENDER = "io.casehub.ledger.api.spi.LedgerAppender";
    private static final String CURRENT_PRINCIPAL = "io.casehub.platform.api.identity.CurrentPrincipal";

    @Produces
    @DefaultBean
    @Singleton
    public AuditEntryWriter auditEntryWriter() {
        AuditEntryWriter logging = new LoggingAuditEntryWriter();
        if (isPresent(LEDGER_APPENDER) && isPresent(CURRENT_PRINCIPAL)) {
            AuditEntryWriter ledger = LedgerSupport.create(logging);
            if (ledger != null) {
                LOG.info("CaseHub audit: writing LangChain4j audit entries to the ledger");
                return ledger;
            }
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

    private static boolean isPresent(String className) {
        try {
            Class.forName(className, false, Thread.currentThread().getContextClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    /** Touches the optional ledger and identity types; loaded only when both are present. */
    private static final class LedgerSupport {

        static AuditEntryWriter create(AuditEntryWriter fallback) {
            InstanceHandle<LedgerAppender> appender = Arc.container().instance(LedgerAppender.class);
            InstanceHandle<CurrentPrincipal> principal = Arc.container().instance(CurrentPrincipal.class);
            if (!appender.isAvailable() || !principal.isAvailable()) {
                return null;
            }
            CurrentPrincipal current = principal.get();
            return new LedgerAuditEntryWriter(appender.get(), current::tenancyId, fallback);
        }
    }
}
