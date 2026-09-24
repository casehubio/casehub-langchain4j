package io.casehub.langchain4j.tenancy;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import io.casehub.platform.identity.CurrentPrincipal;

import java.util.List;

/**
 * Decorator that prefixes memoryId with the current tenant ID,
 * ensuring tenant isolation on any backing ChatMemoryStore.
 * <p>
 * The delegate can be any ChatMemoryStore — Redis, PostgreSQL, in-memory.
 * This decorator adds tenant isolation without replacing the backing store.
 */
public class TenantIsolatingChatMemoryStore implements ChatMemoryStore {

    private final ChatMemoryStore delegate;
    private final CurrentPrincipal currentPrincipal;

    public TenantIsolatingChatMemoryStore(ChatMemoryStore delegate, CurrentPrincipal currentPrincipal) {
        this.delegate = delegate;
        this.currentPrincipal = currentPrincipal;
    }

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        return delegate.getMessages(tenantScoped(memoryId));
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        delegate.updateMessages(tenantScoped(memoryId), messages);
    }

    @Override
    public void deleteMessages(Object memoryId) {
        delegate.deleteMessages(tenantScoped(memoryId));
    }

    private Object tenantScoped(Object memoryId) {
        String tenantId = currentPrincipal.tenancyId();
        return tenantId + "::" + memoryId;
    }
}
