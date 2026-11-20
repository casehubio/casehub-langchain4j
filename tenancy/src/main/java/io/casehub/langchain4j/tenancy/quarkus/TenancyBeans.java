package io.casehub.langchain4j.tenancy.quarkus;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import io.casehub.langchain4j.tenancy.TenantIsolatingChatMemoryStore;
import io.casehub.langchain4j.tenancy.TenantIsolatingContentRetriever;
import io.casehub.langchain4j.tenancy.TenantIsolatingEmbeddingStore;
import io.casehub.platform.api.identity.CurrentPrincipal;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.enterprise.inject.AmbiguousResolutionException;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Default;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.inject.UnsatisfiedResolutionException;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.enterprise.util.TypeLiteral;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

/**
 * Makes an unqualified injection of {@link ChatMemoryStore}, {@code EmbeddingStore<TextSegment>} or
 * {@link ContentRetriever} resolve to a tenant-isolating decorator around the application's own
 * bean of that type.
 *
 * <p>Each producer is an alternative, so it wins over the application's bean, and is lazy: an
 * application that never injects the type never triggers it. The application's bean stays
 * reachable through its own qualifier (for example {@code @Named}).
 *
 * <p>Without a {@link CurrentPrincipal} bean the decorators pass through unchanged.
 */
@ApplicationScoped
public class TenancyBeans {

    private static final Type EMBEDDING_STORE_TYPE = new TypeLiteral<EmbeddingStore<TextSegment>>() {}.getType();

    @Inject
    BeanManager beanManager;

    // The @Any Instance<T> parameters are not read: they are injection points that tell ArC the
    // application's beans of that type are in use, so unused-bean removal keeps them.

    @Produces
    @Alternative
    @Priority(100)
    @Singleton
    public ChatMemoryStore tenantChatMemoryStore(
            @Any Instance<ChatMemoryStore> keepAlive, Instance<CurrentPrincipal> principal) {
        ChatMemoryStore delegate = applicationBean(ChatMemoryStore.class, "ChatMemoryStore");
        return new TenantIsolatingChatMemoryStore(delegate, orNull(principal));
    }

    @Produces
    @Alternative
    @Priority(100)
    @Singleton
    public EmbeddingStore<TextSegment> tenantEmbeddingStore(
            @Any Instance<EmbeddingStore<TextSegment>> keepAlive, Instance<CurrentPrincipal> principal) {
        EmbeddingStore<TextSegment> delegate = applicationBean(EMBEDDING_STORE_TYPE, "EmbeddingStore<TextSegment>");
        return new TenantIsolatingEmbeddingStore(delegate, orNull(principal));
    }

    @Produces
    @Alternative
    @Priority(100)
    @Singleton
    public ContentRetriever tenantContentRetriever(
            @Any Instance<ContentRetriever> keepAlive, Instance<CurrentPrincipal> principal) {
        ContentRetriever delegate = applicationBean(ContentRetriever.class, "ContentRetriever");
        return new TenantIsolatingContentRetriever(delegate, orNull(principal));
    }

    /**
     * Finds the one bean the application would have received without this module: every bean of
     * the type except the producers above, narrowed to the {@code @Default} ones when several
     * exist. Uses {@link BeanManager#getBeans} because it lists all matching beans, whereas
     * {@code Instance} resolution would hide them behind this module's alternative.
     */
    @SuppressWarnings("unchecked")
    private <T> T applicationBean(Type type, String typeName) {
        List<Bean<?>> own = new ArrayList<>();
        for (Bean<?> bean : beanManager.getBeans(type, Any.Literal.INSTANCE)) {
            if (bean.getBeanClass() != TenancyBeans.class) {
                own.add(bean);
            }
        }
        if (own.size() > 1) {
            List<Bean<?>> defaults = own.stream()
                    .filter(b -> b.getQualifiers().stream().anyMatch(Default.class::isInstance))
                    .toList();
            if (defaults.size() == 1) {
                own = defaults;
            }
        }
        if (own.isEmpty()) {
            throw new UnsatisfiedResolutionException("casehub-langchain4j-tenancy found no " + typeName
                    + " bean to isolate — define one, or remove the injection point");
        }
        if (own.size() > 1) {
            throw new AmbiguousResolutionException("casehub-langchain4j-tenancy found " + own.size() + " "
                    + typeName + " beans and cannot tell which one an unqualified injection means — "
                    + "inject them by qualifier and wrap each with its TenantIsolating* decorator");
        }
        Bean<?> bean = own.get(0);
        return (T) beanManager.getReference(bean, type, beanManager.createCreationalContext(bean));
    }

    private static CurrentPrincipal orNull(Instance<CurrentPrincipal> principal) {
        return principal.isResolvable() ? principal.get() : null;
    }
}
