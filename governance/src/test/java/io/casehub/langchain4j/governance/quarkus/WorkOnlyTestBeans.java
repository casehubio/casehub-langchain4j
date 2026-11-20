package io.casehub.langchain4j.governance.quarkus;

import io.casehub.langchain4j.governance.gate.FakeWorkItemApi;
import io.casehub.work.api.spi.WorkItemApi;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

/** A WorkItemApi without any classifier; enabled only by {@link ClassifierDiscoveryTest}. */
@Alternative
@ApplicationScoped
public class WorkOnlyTestBeans {

    public static final FakeWorkItemApi WORK = new FakeWorkItemApi();

    @Produces
    @Singleton
    WorkItemApi workItemApi() {
        return WORK.api;
    }
}
