package io.casehub.langchain4j.hybridsearch.spring;

import dev.langchain4j.rag.content.retriever.ContentRetriever;
import io.casehub.langchain4j.hybridsearch.CasehubHybridContentRetriever;
import io.casehub.langchain4j.hybridsearch.HybridSearchConfig;
import io.casehub.neocortex.rag.CaseContextRetriever;
import io.casehub.neocortex.rag.CaseRetriever;
import io.casehub.platform.api.identity.CurrentPrincipal;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.env.Environment;

/**
 * Registers the neocortex-backed hybrid retriever as the bean {@value #BEAN_NAME}.
 *
 * <p>Hybrid search replaces a retriever rather than decorating one, so the bean is opt-in: it is
 * not a candidate for an unqualified {@code ContentRetriever} injection and never competes with
 * the application's own retriever. Inject it with {@code @Qualifier("casehubHybridRetriever")}.
 *
 * <p>The bean is lazy. It needs a {@link CaseRetriever} bean, the property {@value #CORPORA}, and
 * a {@link CurrentPrincipal} to supply the tenant; a missing piece is reported on first use.
 */
@AutoConfiguration
@ConditionalOnClass({ContentRetriever.class, CaseRetriever.class})
public class HybridSearchAutoConfiguration {

    public static final String BEAN_NAME = "casehubHybridRetriever";
    public static final String CORPORA = "casehub.langchain4j.hybrid-search.corpora";
    public static final String MAX_RESULTS = "casehub.langchain4j.hybrid-search.max-results";

    @Bean(name = BEAN_NAME, defaultCandidate = false)
    @Lazy
    @ConditionalOnMissingBean(name = BEAN_NAME)
    public ContentRetriever casehubHybridRetriever(
            ObjectProvider<CaseRetriever> caseRetriever,
            ObjectProvider<CurrentPrincipal> principal,
            Environment environment) {
        CaseRetriever retriever = caseRetriever.getIfAvailable();
        if (retriever == null) {
            throw new IllegalStateException(
                    BEAN_NAME + " needs a neocortex CaseRetriever bean, and none is defined");
        }
        String corpora = environment.getProperty(CORPORA);
        if (corpora == null || corpora.isBlank()) {
            throw new IllegalStateException(BEAN_NAME + " needs the property " + CORPORA
                    + " — a comma-separated list of neocortex corpus names");
        }
        int maxResults = environment.getProperty(MAX_RESULTS, Integer.class, HybridSearchConfig.DEFAULT_MAX_RESULTS);
        return new CasehubHybridContentRetriever(
                new CaseContextRetriever(retriever),
                () -> {
                    CurrentPrincipal current = principal.getIfAvailable();
                    return current == null ? null : current.tenancyId();
                },
                HybridSearchConfig.of(corpora, maxResults));
    }
}
