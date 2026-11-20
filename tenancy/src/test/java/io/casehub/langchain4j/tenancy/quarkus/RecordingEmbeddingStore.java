package io.casehub.langchain4j.tenancy.quarkus;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class RecordingEmbeddingStore implements EmbeddingStore<TextSegment> {

    public static final List<TextSegment> ADDED = new CopyOnWriteArrayList<>();

    @Override
    public String add(Embedding embedding) {
        return "id";
    }

    @Override
    public void add(String id, Embedding embedding) {}

    @Override
    public String add(Embedding embedding, TextSegment segment) {
        ADDED.add(segment);
        return "id";
    }

    @Override
    public List<String> addAll(List<Embedding> embeddings) {
        return List.of();
    }

    @Override
    public EmbeddingSearchResult<TextSegment> search(EmbeddingSearchRequest request) {
        return new EmbeddingSearchResult<>(List.of());
    }
}
