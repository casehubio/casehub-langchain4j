package io.casehub.langchain4j.tenancy.quarkus;

import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import io.casehub.langchain4j.tenancy.TenantIsolatingChatMemoryStore;
import io.casehub.platform.api.identity.CurrentPrincipal;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import io.quarkus.arc.DefaultBean;

@ApplicationScoped
public class TenancyBeans {

    @Inject
    CurrentPrincipal currentPrincipal;

    @Inject
    @Any
    Instance<ChatMemoryStore> chatMemoryStoreInstance;

    // TODO: Wrap existing ChatMemoryStore, EmbeddingStore, ContentRetriever
    // beans with tenant-isolating decorators. The CDI wiring pattern:
    // 1. Discover the developer's existing bean via Instance<T>
    // 2. Filter out our own decorator (prevent circular)
    // 3. Wrap with TenantIsolating* decorator
    // 4. Produce as @DefaultBean @Priority(10) so it wins injection
}
