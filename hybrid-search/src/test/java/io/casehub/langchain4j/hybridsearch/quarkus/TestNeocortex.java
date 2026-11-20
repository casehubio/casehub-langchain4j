package io.casehub.langchain4j.hybridsearch.quarkus;

import dev.langchain4j.rag.content.retriever.ContentRetriever;
import io.casehub.neocortex.rag.CaseRetriever;
import io.casehub.neocortex.rag.CorpusRef;
import io.casehub.neocortex.rag.RetrievedChunk;
import io.casehub.platform.api.identity.CurrentPrincipal;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/** The beans a real application would get from neocortex and platform, plus its own retriever. */
@ApplicationScoped
public class TestNeocortex {

    public static final List<CorpusRef> SEEN = new CopyOnWriteArrayList<>();
    public static final List<Integer> MAX_RESULTS = new CopyOnWriteArrayList<>();
    public static final ContentRetriever OWN = query -> List.of();

    @Produces
    @Singleton
    CaseRetriever caseRetriever() {
        return (query, corpus, maxResults, filter) -> {
            SEEN.add(corpus);
            MAX_RESULTS.add(maxResults);
            return "kb".equals(corpus.corpusName())
                    ? List.of(new RetrievedChunk("answer", "doc-1", 0.9, Map.of()))
                    : List.of();
        };
    }

    @Produces
    @Singleton
    ContentRetriever ownRetriever() {
        return OWN;
    }

    @Produces
    @Singleton
    CurrentPrincipal currentPrincipal() {
        return new CurrentPrincipal() {
            @Override
            public String actorId() {
                return "tester";
            }

            @Override
            public Set<String> groups() {
                return Set.of();
            }

            @Override
            public String tenancyId() {
                return "acme";
            }

            @Override
            public boolean isCrossTenantAdmin() {
                return false;
            }
        };
    }
}
