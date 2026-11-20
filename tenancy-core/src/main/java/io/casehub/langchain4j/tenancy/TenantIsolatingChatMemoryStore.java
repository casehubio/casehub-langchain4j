package io.casehub.langchain4j.tenancy;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import io.casehub.platform.api.identity.CurrentPrincipal;
import java.util.List;

/**
 * Decorator that scopes every memory ID to the current tenant, so two tenants using the same
 * memory ID never see each other's conversation.
 *
 * <p>The delegate can be any {@link ChatMemoryStore} — Redis, PostgreSQL, in-memory. It sees the
 * memory ID {@code <tenant>::<memoryId>}. See {@link TenantContext} for the tenant rules.
 */
public class TenantIsolatingChatMemoryStore implements ChatMemoryStore {

    private final ChatMemoryStore delegate;
    private final TenantContext tenantContext;

    public TenantIsolatingChatMemoryStore(ChatMemoryStore delegate, CurrentPrincipal principalOrNull) {
        this.delegate = delegate;
        this.tenantContext = new TenantContext(principalOrNull);
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
        return tenantContext.tenantId()
                .<Object>map(tenant -> tenant + TenantContext.SEPARATOR + memoryId)
                .orElse(memoryId);
    }
}
