package io.casehub.langchain4j.hybridsearch;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.ContentMetadata;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import io.casehub.neocortex.rag.CaseContextRetriever;
import io.casehub.neocortex.rag.CorpusRef;
import io.casehub.neocortex.rag.RetrievedChunk;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * {@link ContentRetriever} backed by neocortex's hybrid retrieval pipeline — SPLADE sparse
 * embeddings, dense embeddings, Reciprocal Rank Fusion and optional cross-encoder reranking, all
 * configured inside neocortex.
 *
 * <p>Each query searches the configured corpora of the <em>current tenant</em>. Every returned
 * segment carries {@value #SOURCE_DOCUMENT_ID}, {@value #RELEVANCE_GRADE} and
 * {@value #TENANT_ID} metadata, and the relevance score as {@link ContentMetadata#SCORE}.
 *
 * <p>This module exists because LangChain4j has acknowledged the gap:
 * <a href="https://github.com/langchain4j/langchain4j/issues/4087">langchain4j#4087</a>.
 */
public class CasehubHybridContentRetriever implements ContentRetriever {

    public static final String SOURCE_DOCUMENT_ID = "source_document_id";
    public static final String RELEVANCE_GRADE = "relevance_grade";

    /** Same key the tenancy module uses, so its content filter accepts hybrid results. */
    public static final String TENANT_ID = "casehub_tenant_id";

    private final CaseContextRetriever delegate;
    private final Supplier<String> tenantId;
    private final HybridSearchConfig config;

    /** @param tenantId supplies the current tenant, typically {@code CurrentPrincipal::tenancyId} */
    public CasehubHybridContentRetriever(
            CaseContextRetriever delegate, Supplier<String> tenantId, HybridSearchConfig config) {
        this.delegate = Objects.requireNonNull(delegate, "delegate required");
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId supplier required");
        this.config = Objects.requireNonNull(config, "config required");
    }

    @Override
    public List<Content> retrieve(Query query) {
        String tenant = tenantId.get();
        if (tenant == null || tenant.isBlank()) {
            throw new IllegalStateException(
                    "Hybrid search needs a tenant to select its corpora, but CurrentPrincipal supplied none");
        }
        List<CorpusRef> corpora = config.corpusNames().stream()
                .map(name -> new CorpusRef(tenant, name))
                .toList();
        return delegate.retrieve(query.text(), corpora, config.maxResults()).stream()
                .filter(chunk -> !chunk.content().isBlank())
                .map(chunk -> toContent(chunk, tenant))
                .toList();
    }

    private static Content toContent(RetrievedChunk chunk, String tenant) {
        Map<String, Object> metadata = new LinkedHashMap<>(chunk.metadata());
        metadata.put(SOURCE_DOCUMENT_ID, chunk.sourceDocumentId());
        metadata.put(RELEVANCE_GRADE, chunk.grade().name());
        metadata.put(TENANT_ID, tenant);
        return Content.from(
                TextSegment.from(chunk.content(), Metadata.from(metadata)),
                Map.of(ContentMetadata.SCORE, chunk.relevanceScore()));
    }
}
