package io.casehub.langchain4j.governance.quarkus;

import io.casehub.langchain4j.governance.GovernanceAgentListener;
import io.casehub.langchain4j.governance.GovernanceEntryWriter;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;

@ApplicationScoped
public class GovernanceBeans {

    @Inject
    @Any
    Instance<GovernanceEntryWriter> writerInstance;

    @Produces
    @ApplicationScoped
    public GovernanceAgentListener governanceAgentListener() {
        // TODO: Resolve GovernanceEntryWriter — ledger-backed if available,
        // logging fallback otherwise
        GovernanceEntryWriter writer = writerInstance.isResolvable()
                ? writerInstance.get()
                : new LoggingGovernanceEntryWriter();
        return new GovernanceAgentListener(writer);
    }
}
