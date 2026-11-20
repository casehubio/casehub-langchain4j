package io.casehub.langchain4j.hybridsearch.quarkus;

import dev.langchain4j.rag.content.retriever.ContentRetriever;
import io.casehub.langchain4j.hybridsearch.CasehubHybridContentRetriever;
import io.casehub.langchain4j.hybridsearch.HybridSearchConfig;
import io.casehub.neocortex.rag.CaseContextRetriever;
import io.casehub.neocortex.rag.CaseRetriever;
import io.casehub.platform.api.identity.CurrentPrincipal;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;

/**
 * Produces the neocortex-backed hybrid retriever, injectable as
 * {@code @Inject @HybridSearch ContentRetriever} or {@code @Named("casehubHybridRetriever")}.
 *
 * <p>Hybrid search replaces a retriever rather than decorating one, so the bean is opt-in and
 * never competes with the application's own retriever.
 *
 * <p>The bean is lazy. It needs a {@link CaseRetriever} bean, the property {@value #CORPORA}, and
 * a {@link CurrentPrincipal} to supply the tenant; a missing piece is reported on first use.
 */
@ApplicationScoped
public class HybridSearchBeans {

    public static final String BEAN_NAME = "casehubHybridRetriever";
    public static final String CORPORA = "casehub.langchain4j.hybrid-search.corpora";
    public static final String MAX_RESULTS = "casehub.langchain4j.hybrid-search.max-results";

    @Produces
    @Singleton
    @HybridSearch
    @Named(BEAN_NAME)
    public ContentRetriever hybridContentRetriever(
            Instance<CaseRetriever> caseRetriever, Instance<CurrentPrincipal> principal) {
        if (!caseRetriever.isResolvable()) {
            throw new IllegalStateException(
                    BEAN_NAME + " needs a neocortex CaseRetriever bean, and none is resolvable");
        }
        Config config = ConfigProvider.getConfig();
        String corpora = config.getOptionalValue(CORPORA, String.class).orElse("");
        if (corpora.isBlank()) {
            throw new IllegalStateException(BEAN_NAME + " needs the property " + CORPORA
                    + " — a comma-separated list of neocortex corpus names");
        }
        int maxResults = config.getOptionalValue(MAX_RESULTS, Integer.class)
                .orElse(HybridSearchConfig.DEFAULT_MAX_RESULTS);
        return new CasehubHybridContentRetriever(
                new CaseContextRetriever(caseRetriever.get()),
                () -> principal.isResolvable() ? principal.get().tenancyId() : null,
                HybridSearchConfig.of(corpora, maxResults));
    }
}
