package io.casehub.langchain4j.tenancy.quarkus;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import io.casehub.langchain4j.tenancy.TenantIsolatingChatMemoryStore;
import io.casehub.langchain4j.tenancy.TenantIsolatingContentRetriever;
import io.casehub.langchain4j.tenancy.TenancyWarnings;
import io.casehub.langchain4j.tenancy.TenantIsolatingEmbeddingStore;
import io.casehub.platform.api.identity.CurrentPrincipal;
import io.quarkus.arc.InjectableBean;
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
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

/**
 * Makes an unqualified injection of {@link ChatMemoryStore}, {@code EmbeddingStore<TextSegment>} or
 * {@link ContentRetriever} resolve to a tenant-isolating decorator around the application's own
 * bean of that type.
 *
 * <p>Each producer is an alternative, so it wins over the application's bean. The decorators are
 * {@code @ApplicationScoped}: injection points receive a client proxy and the decorator is built
 * on first use. That keeps an application that never uses the type from triggering it, and lets a
 * class both declare a store and inject one without a creation cycle. The application's bean stays
 * reachable through its own qualifier (for example {@code @Named}). Injecting the store by its
 * concrete class bypasses the decorator.
 *
 * <p>Without a {@link CurrentPrincipal} bean the decorators pass through unchanged, and a WARN is
 * logged for every store wrapped that way.
 */
@ApplicationScoped
public class TenancyBeans {

    private static final Type EMBEDDING_STORE_TYPE = new TypeLiteral<EmbeddingStore<TextSegment>>() {}.getType();

    /** This module's alternative priority; decorating modules that wrap tenancy use a higher one. */
    public static final int PRIORITY = 100;

    @Inject
    BeanManager beanManager;

    // The @Any Instance<T> parameters are not read: they are injection points that tell ArC the
    // application's beans of that type are in use, so unused-bean removal keeps them.

    @Produces
    @Alternative
    @Priority(100)
    @ApplicationScoped
    public ChatMemoryStore tenantChatMemoryStore(
            @Any Instance<ChatMemoryStore> keepAlive, Instance<CurrentPrincipal> principal) {
        ChatMemoryStore delegate = applicationBean(ChatMemoryStore.class, "ChatMemoryStore");
        return new TenantIsolatingChatMemoryStore(delegate, orNull(principal, "ChatMemoryStore"));
    }

    @Produces
    @Alternative
    @Priority(100)
    @ApplicationScoped
    public EmbeddingStore<TextSegment> tenantEmbeddingStore(
            @Any Instance<EmbeddingStore<TextSegment>> keepAlive, Instance<CurrentPrincipal> principal) {
        EmbeddingStore<TextSegment> delegate = applicationBean(EMBEDDING_STORE_TYPE, "EmbeddingStore<TextSegment>");
        return new TenantIsolatingEmbeddingStore(delegate, orNull(principal, "EmbeddingStore"));
    }

    @Produces
    @Alternative
    @Priority(100)
    @ApplicationScoped
    public ContentRetriever tenantContentRetriever(
            @Any Instance<ContentRetriever> keepAlive, Instance<CurrentPrincipal> principal) {
        ContentRetriever delegate = applicationBean(ContentRetriever.class, "ContentRetriever");
        return new TenantIsolatingContentRetriever(delegate, orNull(principal, "ContentRetriever"));
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
        own = narrowLikeArc(own);
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

    /**
     * Applies the resolution rules ArC would have applied had this module's alternative not been
     * there: only {@code @Default} beans are unqualified candidates; enabled alternatives win, the
     * highest priority first; {@code @DefaultBean}s lose to any other bean.
     */
    private static List<Bean<?>> narrowLikeArc(List<Bean<?>> beans) {
        // A decorating alternative that outranks this module's own (for example crag at 200) wraps
        // tenancy, never the reverse: choosing it as the delegate would make the two wrap each other.
        List<Bean<?>> candidates = beans.stream()
                .filter(b -> b.getQualifiers().stream().anyMatch(Default.class::isInstance))
                .filter(b -> !(b.isAlternative() && priority(b) > PRIORITY))
                .toList();
        if (candidates.size() <= 1) {
            return candidates.isEmpty() ? beans : candidates;
        }
        List<Bean<?>> alternatives = candidates.stream().filter(Bean::isAlternative).toList();
        if (!alternatives.isEmpty()) {
            int top = alternatives.stream().mapToInt(TenancyBeans::priority).max().getAsInt();
            return alternatives.stream().filter(b -> priority(b) == top).toList();
        }
        List<Bean<?>> nonDefaultBeans = candidates.stream()
                .filter(b -> !(b instanceof InjectableBean<?> injectable && injectable.isDefaultBean()))
                .toList();
        return nonDefaultBeans.isEmpty() ? candidates : nonDefaultBeans;
    }

    private static int priority(Bean<?> bean) {
        return bean instanceof InjectableBean<?> injectable ? injectable.getPriority() : 0;
    }

    private static CurrentPrincipal orNull(Instance<CurrentPrincipal> principal, String wrapped) {
        if (principal.isResolvable()) {
            return principal.get();
        }
        TenancyWarnings.noPrincipal(wrapped);
        return null;
    }
}
