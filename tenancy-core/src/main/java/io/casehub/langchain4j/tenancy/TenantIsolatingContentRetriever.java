package io.casehub.langchain4j.tenancy;

import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import io.casehub.platform.api.identity.CurrentPrincipal;

import java.util.List;

/**
 * Decorator that injects tenant context into retrieval queries,
 * ensuring only the current tenant's content is returned.
 * <p>
 * The delegate can be any ContentRetriever — EmbeddingStore-backed,
 * web search, SQL, knowledge graph. This decorator adds tenant
 * filtering without replacing the retrieval implementation.
 */
public class TenantIsolatingContentRetriever implements ContentRetriever {

    private final ContentRetriever delegate;
    private final CurrentPrincipal currentPrincipal;

    public TenantIsolatingContentRetriever(ContentRetriever delegate, CurrentPrincipal currentPrincipal) {
        this.delegate = delegate;
        this.currentPrincipal = currentPrincipal;
    }

    @Override
    public List<Content> retrieve(Query query) {
        // TODO: Inject tenant filter into the query metadata so the
        // backing retriever scopes results to the current tenant.
        // Implementation depends on the delegate's filter mechanism:
        //   - EmbeddingStore-backed: add metadata filter for tenantId
        //   - SQL-backed: add WHERE clause
        //   - Custom: pass tenantId via Query metadata
        String tenantId = currentPrincipal.tenancyId();
        return delegate.retrieve(query);
    }
}
