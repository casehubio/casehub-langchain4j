package io.casehub.langchain4j.tenancy.quarkus;

import static org.assertj.core.api.Assertions.assertThat;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import dev.langchain4j.store.memory.chat.InMemoryChatMemoryStore;
import io.casehub.langchain4j.tenancy.TenantIsolatingChatMemoryStore;
import io.casehub.langchain4j.tenancy.TenantIsolatingContentRetriever;
import io.casehub.langchain4j.tenancy.TenantIsolatingEmbeddingStore;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class TenancyBeansTest {

    @Inject
    ChatMemoryStore store;

    @Inject
    @Named("raw")
    ChatMemoryStore rawStore;

    @Inject
    ContentRetriever retriever;

    @Inject
    EmbeddingStore<TextSegment> embeddingStore;

    @Inject
    SwitchablePrincipal principal;

    @BeforeEach
    void resetTenant() {
        principal.tenant = "a";
    }

    @Test
    void unqualifiedInjection_isTenantWrapper() {
        assertThat(store).isInstanceOf(TenantIsolatingChatMemoryStore.class);
    }

    @Test
    void twoTenants_sameMemoryId_noLeakage() {
        List<ChatMessage> messages = List.of(UserMessage.from("from a"));

        store.updateMessages("leak-test", messages);
        principal.tenant = "b";
        assertThat(store.getMessages("leak-test")).isEmpty();
        principal.tenant = "a";
        assertThat(store.getMessages("leak-test")).isEqualTo(messages);
    }

    @Test
    void namedRawStore_stillInjectable() {
        assertThat(rawStore).isInstanceOf(InMemoryChatMemoryStore.class);

        store.updateMessages("raw-test", List.of(UserMessage.from("hi")));

        assertThat(rawStore.getMessages("a::raw-test")).hasSize(1);
    }

    @Test
    void unqualifiedContentRetriever_filtersByTenant() {
        assertThat(retriever).isInstanceOf(TenantIsolatingContentRetriever.class);
        assertThat(retriever.retrieve(Query.from("q")))
                .extracting(c -> c.textSegment().text())
                .containsExactly("a's doc");
    }

    @Test
    void unqualifiedEmbeddingStore_tagsWithTenant() {
        assertThat(embeddingStore).isInstanceOf(TenantIsolatingEmbeddingStore.class);

        embeddingStore.add(Embedding.from(new float[] {1f}), TextSegment.from("doc"));

        assertThat(RecordingEmbeddingStore.ADDED)
                .anySatisfy(s -> assertThat(s.metadata().getString("casehub_tenant_id")).isEqualTo("a"));
    }
}
