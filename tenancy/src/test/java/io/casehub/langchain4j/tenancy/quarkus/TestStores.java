package io.casehub.langchain4j.tenancy.quarkus;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import dev.langchain4j.store.memory.chat.InMemoryChatMemoryStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.util.List;

/** The "application's own" beans that the tenancy module must wrap. */
@ApplicationScoped
public class TestStores {

    /**
     * A bean that declares a store and also injects one — the common shape of an application
     * class. Creating the tenant wrapper must not recurse through this injection point.
     */
    @Inject
    ChatMemoryStore injectedIntoTheDeclaringBean;

    public ChatMemoryStore injected() {
        return injectedIntoTheDeclaringBean;
    }

    @Produces
    @Singleton
    @Named("raw")
    ChatMemoryStore rawChatMemoryStore() {
        return new InMemoryChatMemoryStore();
    }

    @Produces
    @Singleton
    EmbeddingStore<TextSegment> appEmbeddingStore() {
        return new RecordingEmbeddingStore();
    }

    @Produces
    @Singleton
    ContentRetriever appContentRetriever() {
        return query -> List.of(
                Content.from(TextSegment.from("a's doc", Metadata.from("casehub_tenant_id", "a"))),
                Content.from(TextSegment.from("b's doc", Metadata.from("casehub_tenant_id", "b"))));
    }
}
