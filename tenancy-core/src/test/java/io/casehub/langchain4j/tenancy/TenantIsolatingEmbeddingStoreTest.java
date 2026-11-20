package io.casehub.langchain4j.tenancy;

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import java.util.List;
import org.junit.jupiter.api.Test;

class TenantIsolatingEmbeddingStoreTest {

    private static final String KEY = "casehub_tenant_id";

    private final MutablePrincipal principal = new MutablePrincipal("a");
    private final FakeEmbeddingStore delegate = new FakeEmbeddingStore();
    private final TenantIsolatingEmbeddingStore store = new TenantIsolatingEmbeddingStore(delegate, principal);
    private final Embedding embedding = Embedding.from(new float[] {1f, 0f});

    private EmbeddingSearchRequest.EmbeddingSearchRequestBuilder search() {
        return EmbeddingSearchRequest.builder().queryEmbedding(embedding).maxResults(10);
    }

    private List<String> texts(EmbeddingSearchRequest request) {
        return store.search(request).matches().stream().map(EmbeddingMatch::embedded).map(TextSegment::text).toList();
    }

    @Test
    void add_tagsSegmentWithTenant() {
        String id = store.add(embedding, TextSegment.from("doc", Metadata.from("category", "x")));

        Metadata stored = delegate.entries.get(id).segment().metadata();
        assertThat(stored.getString(KEY)).isEqualTo("a");
        assertThat(stored.getString("category")).isEqualTo("x");
    }

    @Test
    void add_doesNotMutateCallersSegment() {
        TextSegment original = TextSegment.from("doc");

        store.add(embedding, original);

        assertThat(original.metadata().getString(KEY)).isNull();
    }

    @Test
    void add_overwritesCallerSuppliedTenantKey() {
        String id = store.add(embedding, TextSegment.from("doc", Metadata.from(KEY, "b")));

        assertThat(delegate.entries.get(id).segment().metadata().getString(KEY)).isEqualTo("a");
        principal.tenant = "b";
        assertThat(texts(search().build())).isEmpty();
    }

    @Test
    void search_returnsOnlyOwnTenant() {
        store.add(embedding, TextSegment.from("from a"));
        principal.tenant = "b";
        store.add(embedding, TextSegment.from("from b"));

        principal.tenant = "a";
        assertThat(texts(search().build())).containsExactly("from a");
    }

    @Test
    void search_andsExistingFilter() {
        store.add(embedding, TextSegment.from("x doc", Metadata.from("category", "x")));
        store.add(embedding, TextSegment.from("y doc", Metadata.from("category", "y")));
        principal.tenant = "b";
        store.add(embedding, TextSegment.from("b's x doc", Metadata.from("category", "x")));

        principal.tenant = "a";
        assertThat(texts(search().filter(metadataKey("category").isEqualTo("x")).build()))
                .containsExactly("x doc");
    }

    @Test
    void search_preservesOtherRequestFields() {
        store.search(EmbeddingSearchRequest.builder()
                .queryEmbedding(embedding).query("what?").maxResults(3).minScore(0.5).build());

        assertThat(delegate.lastRequest.maxResults()).isEqualTo(3);
        assertThat(delegate.lastRequest.minScore()).isEqualTo(0.5);
        assertThat(delegate.lastRequest.query()).isEqualTo("what?");
        assertThat(delegate.lastRequest.queryEmbedding()).isEqualTo(embedding);
    }

    @Test
    void addAllWithSegments_tagsEach() {
        store.addAll(List.of(embedding, embedding), List.of(TextSegment.from("one"), TextSegment.from("two")));
        store.addAll(List.of("id3"), List.of(embedding), List.of(TextSegment.from("three")));

        assertThat(delegate.entries).hasSize(3);
        assertThat(delegate.entries.values())
                .allSatisfy(e -> assertThat(e.segment().metadata().getString(KEY)).isEqualTo("a"));
    }

    @Test
    void addWithoutSegment_throwsUnsupportedOperation() {
        assertThatThrownBy(() -> store.add(embedding))
                .isInstanceOf(UnsupportedOperationException.class).hasMessageContaining("add(Embedding, TextSegment)");
        assertThatThrownBy(() -> store.add("id", embedding))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> store.addAll(List.of(embedding)))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> store.add(embedding, null))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(delegate.entries).isEmpty();
    }

    @Test
    void removeAllByFilter_scopedToTenant() {
        store.add(embedding, TextSegment.from("a's x", Metadata.from("category", "x")));
        principal.tenant = "b";
        store.add(embedding, TextSegment.from("b's x", Metadata.from("category", "x")));

        store.removeAll(metadataKey("category").isEqualTo("x"));

        assertThat(texts(search().build())).isEmpty();
        principal.tenant = "a";
        assertThat(texts(search().build())).containsExactly("a's x");
    }

    @Test
    void removeAll_removesOnlyOwnTenant() {
        store.add(embedding, TextSegment.from("from a"));
        principal.tenant = "b";
        store.add(embedding, TextSegment.from("from b"));

        store.removeAll();

        assertThat(delegate.entries).hasSize(1);
        principal.tenant = "a";
        assertThat(texts(search().build())).containsExactly("from a");
    }

    @Test
    void nullPrincipal_passesThrough() {
        TenantIsolatingEmbeddingStore passthrough = new TenantIsolatingEmbeddingStore(delegate, null);

        String id = passthrough.add(embedding, TextSegment.from("doc"));
        passthrough.add(embedding);
        passthrough.search(search().build());

        assertThat(delegate.entries.get(id).segment().metadata().getString(KEY)).isNull();
        assertThat(delegate.entries).hasSize(2);
        assertThat(delegate.lastRequest.filter()).isNull();
    }

    @Test
    void blankTenant_throws() {
        principal.tenant = "";

        assertThatThrownBy(() -> store.add(embedding, TextSegment.from("doc")))
                .isInstanceOf(TenantContextMissingException.class);
        assertThatThrownBy(() -> store.search(search().build())).isInstanceOf(TenantContextMissingException.class);
        assertThatThrownBy(() -> store.removeAll()).isInstanceOf(TenantContextMissingException.class);
    }

    @Test
    void removeById_withActiveTenant_throws_becauseIdsCarryNoTenant() {
        String id = store.add(embedding, TextSegment.from("doc"));

        assertThatThrownBy(() -> store.remove(id))
                .isInstanceOf(UnsupportedOperationException.class).hasMessageContaining("removeAll(Filter)");
        assertThatThrownBy(() -> store.removeAll(List.of(id)))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(delegate.entries).containsKey(id);
    }

    @Test
    void removeById_withoutPrincipal_passesThrough() {
        TenantIsolatingEmbeddingStore passthrough = new TenantIsolatingEmbeddingStore(delegate, null);
        String id = passthrough.add(embedding, TextSegment.from("doc"));

        passthrough.removeAll(List.of(id));

        assertThat(delegate.entries).isEmpty();
    }
}
