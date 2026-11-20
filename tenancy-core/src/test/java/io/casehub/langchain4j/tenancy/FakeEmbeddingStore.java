package io.casehub.langchain4j.tenancy;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Minimal in-memory store that honours metadata filters; every stored embedding matches. */
class FakeEmbeddingStore implements EmbeddingStore<TextSegment> {

    record Entry(Embedding embedding, TextSegment segment) {}

    final Map<String, Entry> entries = new LinkedHashMap<>();
    EmbeddingSearchRequest lastRequest;

    @Override
    public String add(Embedding embedding) {
        return add(embedding, null);
    }

    @Override
    public void add(String id, Embedding embedding) {
        entries.put(id, new Entry(embedding, null));
    }

    @Override
    public String add(Embedding embedding, TextSegment segment) {
        String id = UUID.randomUUID().toString();
        entries.put(id, new Entry(embedding, segment));
        return id;
    }

    @Override
    public List<String> addAll(List<Embedding> embeddings) {
        List<String> ids = new ArrayList<>();
        embeddings.forEach(e -> ids.add(add(e)));
        return ids;
    }

    @Override
    public void addAll(List<String> ids, List<Embedding> embeddings, List<TextSegment> segments) {
        for (int i = 0; i < ids.size(); i++) {
            entries.put(ids.get(i), new Entry(embeddings.get(i), segments == null ? null : segments.get(i)));
        }
    }

    @Override
    public void removeAll(Collection<String> ids) {
        ids.forEach(entries::remove);
    }

    @Override
    public void removeAll(Filter filter) {
        entries.values().removeIf(e -> e.segment() != null && filter.test(e.segment().metadata()));
    }

    @Override
    public void removeAll() {
        entries.clear();
    }

    @Override
    public EmbeddingSearchResult<TextSegment> search(EmbeddingSearchRequest request) {
        lastRequest = request;
        List<EmbeddingMatch<TextSegment>> matches = new ArrayList<>();
        entries.forEach((id, e) -> {
            boolean passes = request.filter() == null
                    || (e.segment() != null && request.filter().test(e.segment().metadata()));
            if (passes && matches.size() < request.maxResults()) {
                matches.add(new EmbeddingMatch<>(1.0, id, e.embedding(), e.segment()));
            }
        });
        return new EmbeddingSearchResult<>(matches);
    }
}
