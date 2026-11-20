package io.casehub.langchain4j.tenancy;

import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import io.casehub.platform.api.identity.CurrentPrincipal;
import java.util.List;
import java.util.Optional;

/**
 * Decorator that drops every retrieved content not tagged with the current tenant.
 *
 * <p>This is a post-retrieval safety net for any {@link ContentRetriever} — embedding-backed, SQL,
 * web search. Content without the {@value TenantContext#METADATA_KEY} tag is dropped too: when a
 * tenant is active, untagged content is not known to be theirs. See {@link TenantContext} for the
 * tenant rules.
 */
public class TenantIsolatingContentRetriever implements ContentRetriever {

    private final ContentRetriever delegate;
    private final TenantContext tenantContext;

    public TenantIsolatingContentRetriever(ContentRetriever delegate, CurrentPrincipal principalOrNull) {
        this.delegate = delegate;
        this.tenantContext = new TenantContext(principalOrNull);
    }

    @Override
    public List<Content> retrieve(Query query) {
        Optional<String> tenant = tenantContext.tenantId();
        List<Content> contents = delegate.retrieve(query);
        if (tenant.isEmpty() || contents == null) {
            return contents;
        }
        return contents.stream().filter(content -> ownedBy(content, tenant.get())).toList();
    }

    private static boolean ownedBy(Content content, String tenant) {
        return content != null
                && content.textSegment() != null
                && tenant.equals(content.textSegment().metadata().getString(TenantContext.METADATA_KEY));
    }
}
