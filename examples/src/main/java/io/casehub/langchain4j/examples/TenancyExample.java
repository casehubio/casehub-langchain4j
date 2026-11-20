package io.casehub.langchain4j.examples;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import dev.langchain4j.store.memory.chat.InMemoryChatMemoryStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.ArrayList;
import java.util.List;

/**
 * Tenancy: the application's own {@link ChatMemoryStore} becomes multi-tenant.
 *
 * <p>The application defines an ordinary store ({@link #applicationStore()}) and injects
 * {@code ChatMemoryStore} as usual. With {@code casehub-langchain4j-tenancy} on the classpath the
 * injected bean is a tenant-isolating decorator around that store — no code change.
 */
@ApplicationScoped
public class TenancyExample {

    @Inject
    ChatMemoryStore store;

    /** The application's own store: any implementation works — Redis, PostgreSQL, in-memory. */
    @Produces
    @Singleton
    ChatMemoryStore applicationStore() {
        return new InMemoryChatMemoryStore();
    }

    public void remember(String conversationId, String text) {
        List<ChatMessage> messages = new ArrayList<>(store.getMessages(conversationId));
        messages.add(UserMessage.from(text));
        store.updateMessages(conversationId, messages);
    }

    public List<String> recall(String conversationId) {
        return store.getMessages(conversationId).stream()
                .map(message -> ((UserMessage) message).singleText())
                .toList();
    }
}
