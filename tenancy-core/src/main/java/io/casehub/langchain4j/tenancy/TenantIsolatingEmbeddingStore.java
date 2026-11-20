package io.casehub.langchain4j.tenancy;

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import io.casehub.platform.api.identity.CurrentPrincipal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Decorator that tags every stored segment with the current tenant and restricts every search and
 * filter-based removal to that tenant.
 *
 * <p>The delegate can be any {@link EmbeddingStore} that supports metadata filtering. See
 * {@link TenantContext} for the tenant rules.
 *
 * <p>Limits, by design:
 *
 * <ul>
 *   <li>Embeddings added without a {@link TextSegment} cannot carry a tenant tag, so those
 *       methods throw {@link UnsupportedOperationException} while a tenant is active.
 *   <li>{@link #remove(String)} and {@link #removeAll(Collection)} throw while a tenant is
 *       active: an embedding ID carries no tenant, so the removal could reach another tenant's
 *       data. Use {@link #removeAll(Filter)}, which is restricted to the current tenant.
 *   <li>{@link #addAll(List, List, List)} with caller-chosen IDs is an upsert in most stores. An ID
 *       already used by another tenant replaces that tenant's entry. Let the store generate IDs
 *       (or use random UUIDs) on a shared store.
 * </ul>
 */
public class TenantIsolatingEmbeddingStore implements EmbeddingStore<TextSegment> {

    private final EmbeddingStore<TextSegment> delegate;
    private final TenantContext tenantContext;

    public TenantIsolatingEmbeddingStore(EmbeddingStore<TextSegment> delegate, CurrentPrincipal principalOrNull) {
        this.delegate = delegate;
        this.tenantContext = new TenantContext(principalOrNull);
    }

    @Override
    public String add(Embedding embedding) {
        requireNoTenant("add(Embedding)");
        return delegate.add(embedding);
    }

    @Override
    public void add(String id, Embedding embedding) {
        requireNoTenant("add(String, Embedding)");
        delegate.add(id, embedding);
    }

    @Override
    public String add(Embedding embedding, TextSegment segment) {
        Optional<String> tenant = tenantContext.tenantId();
        return delegate.add(embedding, tenant.isPresent() ? tagged(segment, tenant.get()) : segment);
    }

    @Override
    public List<String> addAll(List<Embedding> embeddings) {
        requireNoTenant("addAll(List<Embedding>)");
        return delegate.addAll(embeddings);
    }

    @Override
    public List<String> addAll(List<Embedding> embeddings, List<TextSegment> segments) {
        return delegate.addAll(embeddings, tagged(segments));
    }

    @Override
    public void addAll(List<String> ids, List<Embedding> embeddings, List<TextSegment> segments) {
        delegate.addAll(ids, embeddings, tagged(segments));
    }

    @Override
    public List<String> generateIds(int n) {
        return delegate.generateIds(n);
    }

    @Override
    public void remove(String id) {
        requireNoTenant("remove(String)", "removeAll(Filter)");
        delegate.remove(id);
    }

    @Override
    public void removeAll(Collection<String> ids) {
        requireNoTenant("removeAll(Collection<String>)", "removeAll(Filter)");
        delegate.removeAll(ids);
    }

    @Override
    public void removeAll(Filter filter) {
        Optional<String> tenant = tenantContext.tenantId();
        delegate.removeAll(tenant.isPresent() ? Filter.and(filter, tenantFilter(tenant.get())) : filter);
    }

    @Override
    public void removeAll() {
        Optional<String> tenant = tenantContext.tenantId();
        if (tenant.isPresent()) {
            delegate.removeAll(tenantFilter(tenant.get()));
        } else {
            delegate.removeAll();
        }
    }

    @Override
    public EmbeddingSearchResult<TextSegment> search(EmbeddingSearchRequest request) {
        Optional<String> tenant = tenantContext.tenantId();
        if (tenant.isEmpty()) {
            return delegate.search(request);
        }
        Filter tenantFilter = tenantFilter(tenant.get());
        return delegate.search(EmbeddingSearchRequest.builder()
                .query(request.query())
                .queryEmbedding(request.queryEmbedding())
                .maxResults(request.maxResults())
                .minScore(request.minScore())
                .filter(request.filter() == null ? tenantFilter : Filter.and(request.filter(), tenantFilter))
                .build());
    }

    private void requireNoTenant(String method) {
        requireNoTenant(method, "add(Embedding, TextSegment) or addAll(List<Embedding>, List<TextSegment>)");
    }

    private void requireNoTenant(String method, String alternative) {
        if (tenantContext.tenantId().isPresent()) {
            throw new UnsupportedOperationException(method
                    + " cannot be restricted to the current tenant — use " + alternative
                    + " on a tenant-isolated store");
        }
    }

    private List<TextSegment> tagged(List<TextSegment> segments) {
        Optional<String> tenant = tenantContext.tenantId();
        if (tenant.isEmpty()) {
            return segments;
        }
        if (segments == null) {
            throw untaggable();
        }
        return segments.stream().map(segment -> tagged(segment, tenant.get())).toList();
    }

    private static TextSegment tagged(TextSegment segment, String tenant) {
        if (segment == null) {
            throw untaggable();
        }
        Metadata metadata = segment.metadata().copy();
        metadata.put(TenantContext.METADATA_KEY, tenant);
        return TextSegment.from(segment.text(), metadata);
    }

    private static UnsupportedOperationException untaggable() {
        return new UnsupportedOperationException(
                "A tenant-isolated store needs a TextSegment for every embedding to tag it with the tenant"
                        + " — use add(Embedding, TextSegment)");
    }

    private static Filter tenantFilter(String tenant) {
        return metadataKey(TenantContext.METADATA_KEY).isEqualTo(tenant);
    }
}
