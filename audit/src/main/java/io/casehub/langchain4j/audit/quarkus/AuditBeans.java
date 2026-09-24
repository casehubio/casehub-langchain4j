package io.casehub.langchain4j.audit.quarkus;

import io.casehub.langchain4j.audit.AuditEntryWriter;
import io.casehub.langchain4j.audit.CasehubChatModelListener;
import io.casehub.langchain4j.audit.LoggingAuditEntryWriter;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;

@ApplicationScoped
public class AuditBeans {

    @Inject
    @Any
    Instance<AuditEntryWriter> writerInstance;

    @Produces
    @ApplicationScoped
    public CasehubChatModelListener chatModelListener() {
        AuditEntryWriter writer = writerInstance.isResolvable()
                ? writerInstance.get()
                : new LoggingAuditEntryWriter();
        return new CasehubChatModelListener(writer);
    }

    // TODO: Produce CasehubAgentListener when langchain4j-agentic is on classpath
}
