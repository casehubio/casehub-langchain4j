package io.casehub.langchain4j.hybridsearch.quarkus;

import io.casehub.langchain4j.hybridsearch.CasehubHybridContentRetriever;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

@ApplicationScoped
public class HybridSearchBeans {

    @Produces
    @ApplicationScoped
    public CasehubHybridContentRetriever hybridContentRetriever() {
        // TODO: inject CaseContextRetriever from neocortex
        return new CasehubHybridContentRetriever();
    }
}
