package io.casehub.langchain4j.tenancy.spring;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import io.casehub.langchain4j.tenancy.TenantIsolatingChatMemoryStore;
import io.casehub.langchain4j.tenancy.TenantIsolatingContentRetriever;
import io.casehub.langchain4j.tenancy.TenancyWarnings;
import io.casehub.langchain4j.tenancy.TenantIsolatingEmbeddingStore;
import io.casehub.platform.api.identity.CurrentPrincipal;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;

/**
 * Wraps every {@link ChatMemoryStore}, {@link EmbeddingStore} and {@link ContentRetriever} bean in
 * the application with its tenant-isolating decorator.
 *
 * <p>The wrapping is done by a {@link BeanPostProcessor}, so the bean keeps its name and the
 * application's own store stays inside the decorator. Consequently a wrapped bean can only be
 * injected by its interface, not by its concrete class.
 *
 * <p>Without a {@link CurrentPrincipal} bean the decorators pass through unchanged, and a WARN is
 * logged for every store wrapped that way.
 */
@AutoConfiguration
@ConditionalOnClass({CurrentPrincipal.class, ChatMemoryStore.class})
public class TenancyAutoConfiguration {

    @Bean
    static TenantIsolatingBeanPostProcessor tenantIsolatingBeanPostProcessor(
            ObjectProvider<CurrentPrincipal> principal) {
        return new TenantIsolatingBeanPostProcessor(principal);
    }

    static final class TenantIsolatingBeanPostProcessor implements BeanPostProcessor {

        private final ObjectProvider<CurrentPrincipal> principal;

        TenantIsolatingBeanPostProcessor(ObjectProvider<CurrentPrincipal> principal) {
            this.principal = principal;
        }

        @Override
        @SuppressWarnings("unchecked")
        public Object postProcessAfterInitialization(Object bean, String beanName) {
            if (bean instanceof TenantIsolatingChatMemoryStore
                    || bean instanceof TenantIsolatingEmbeddingStore
                    || bean instanceof TenantIsolatingContentRetriever) {
                return bean;
            }
            if (bean instanceof ChatMemoryStore store) {
                return new TenantIsolatingChatMemoryStore(store, principal("ChatMemoryStore " + beanName));
            }
            if (bean instanceof EmbeddingStore<?> store) {
                return new TenantIsolatingEmbeddingStore(
                        (EmbeddingStore<TextSegment>) store, principal("EmbeddingStore " + beanName));
            }
            if (bean instanceof ContentRetriever retriever) {
                return new TenantIsolatingContentRetriever(retriever, principal("ContentRetriever " + beanName));
            }
            return bean;
        }

        private CurrentPrincipal principal(String wrapped) {
            CurrentPrincipal current = principal.getIfAvailable();
            if (current == null) {
                TenancyWarnings.noPrincipal(wrapped);
            }
            return current;
        }
    }
}
