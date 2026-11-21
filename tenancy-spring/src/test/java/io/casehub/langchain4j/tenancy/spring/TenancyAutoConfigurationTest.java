package io.casehub.langchain4j.tenancy.spring;

import static org.assertj.core.api.Assertions.assertThat;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import dev.langchain4j.store.memory.chat.InMemoryChatMemoryStore;
import io.casehub.langchain4j.tenancy.TenancyWarnings;
import io.casehub.langchain4j.tenancy.TenantIsolatingChatMemoryStore;
import io.casehub.langchain4j.tenancy.TenantIsolatingContentRetriever;
import io.casehub.langchain4j.tenancy.TenantIsolatingEmbeddingStore;
import io.casehub.platform.api.identity.CurrentPrincipal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class TenancyAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TenancyAutoConfiguration.class));

    @Test
    void wrapsUserChatMemoryStore() {
        runner.withUserConfiguration(PrincipalConfig.class, StoreConfig.class).run(context ->
                assertThat(context.getBean(ChatMemoryStore.class)).isInstanceOf(TenantIsolatingChatMemoryStore.class));
    }

    @Test
    void twoTenants_sameMemoryId_noLeakage() {
        runner.withUserConfiguration(PrincipalConfig.class, StoreConfig.class).run(context -> {
            SwitchablePrincipal principal = context.getBean(SwitchablePrincipal.class);
            ChatMemoryStore store = context.getBean(ChatMemoryStore.class);
            List<ChatMessage> messages = List.of(UserMessage.from("from a"));

            principal.tenant = "a";
            store.updateMessages("m1", messages);
            principal.tenant = "b";
            assertThat(store.getMessages("m1")).isEmpty();
            principal.tenant = "a";
            assertThat(store.getMessages("m1")).isEqualTo(messages);
        });
    }

    @Test
    void noStoreBean_contextStartsWithoutWrapper() {
        runner.withUserConfiguration(PrincipalConfig.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(ChatMemoryStore.class);
            assertThat(context).doesNotHaveBean(EmbeddingStore.class);
            assertThat(context).doesNotHaveBean(ContentRetriever.class);
        });
    }

    @Test
    void doesNotDoubleWrap() {
        runner.withUserConfiguration(PrincipalConfig.class, AlreadyWrappedConfig.class).run(context ->
                assertThat(context.getBean(ChatMemoryStore.class))
                        .isSameAs(context.getBean(AlreadyWrappedConfig.class).wrapped));
    }

    @Test
    void wrapsUserEmbeddingStore_andTagsWithTenant() {
        runner.withUserConfiguration(PrincipalConfig.class, StoreConfig.class).run(context -> {
            context.getBean(SwitchablePrincipal.class).tenant = "a";
            @SuppressWarnings("unchecked")
            EmbeddingStore<TextSegment> store = context.getBean(EmbeddingStore.class);
            assertThat(store).isInstanceOf(TenantIsolatingEmbeddingStore.class);

            store.add(Embedding.from(new float[] {1f}), TextSegment.from("doc"));

            RecordingEmbeddingStore raw = context.getBean(StoreConfig.class).rawEmbeddingStore;
            assertThat(raw.added.get(0).metadata().getString("casehub_tenant_id")).isEqualTo("a");
        });
    }

    @Test
    void wrapsUserContentRetriever_andFiltersByTenant() {
        runner.withUserConfiguration(PrincipalConfig.class, StoreConfig.class).run(context -> {
            context.getBean(SwitchablePrincipal.class).tenant = "a";
            ContentRetriever retriever = context.getBean(ContentRetriever.class);
            assertThat(retriever).isInstanceOf(TenantIsolatingContentRetriever.class);

            assertThat(retriever.retrieve(Query.from("q")))
                    .extracting(c -> c.textSegment().text())
                    .containsExactly("a's doc");
        });
    }

    /** Captures records of the tenancy warning logger (JBoss LogManager implements java.util.logging). */
    private static List<String> warningsDuring(Runnable action) {
        java.util.logging.Logger logger = java.util.logging.Logger.getLogger(TenancyWarnings.class.getName());
        List<String> messages = new java.util.concurrent.CopyOnWriteArrayList<>();
        java.util.logging.Handler handler = new java.util.logging.Handler() {
            @Override
            public void publish(java.util.logging.LogRecord record) {
                messages.add(String.valueOf(record.getMessage()));
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        logger.addHandler(handler);
        try {
            action.run();
        } finally {
            logger.removeHandler(handler);
        }
        return messages;
    }

    @Test
    void noPrincipalBean_warnsThatIsolationIsInactive() {
        List<String> warnings = warningsDuring(() -> runner.withUserConfiguration(StoreConfig.class)
                .run(context -> context.getBean(ChatMemoryStore.class)));

        assertThat(warnings).anySatisfy(w -> assertThat(w).contains("no CurrentPrincipal bean").contains("NOT tenant-isolated"));
    }

    @Test
    void principalBean_doesNotWarn() {
        List<String> warnings = warningsDuring(() -> runner.withUserConfiguration(PrincipalConfig.class, StoreConfig.class)
                .run(context -> context.getBean(ChatMemoryStore.class)));

        assertThat(warnings).noneMatch(w -> w.contains("NOT tenant-isolated"));
    }

    @Test
    void noPrincipalBean_wrapperPassesThrough() {
        runner.withUserConfiguration(StoreConfig.class).run(context -> {
            assertThat(context).hasNotFailed();
            ContentRetriever retriever = context.getBean(ContentRetriever.class);
            assertThat(retriever.retrieve(Query.from("q"))).hasSize(2);
        });
    }

    static class SwitchablePrincipal implements CurrentPrincipal {
        volatile String tenant = "a";

        @Override
        public String actorId() {
            return "tester";
        }

        @Override
        public Set<String> groups() {
            return Set.of();
        }

        @Override
        public String tenancyId() {
            return tenant;
        }

        @Override
        public boolean isCrossTenantAdmin() {
            return false;
        }
    }

    static class RecordingEmbeddingStore implements EmbeddingStore<TextSegment> {
        final List<TextSegment> added = new ArrayList<>();

        @Override
        public String add(Embedding embedding) {
            return "id";
        }

        @Override
        public void add(String id, Embedding embedding) {}

        @Override
        public String add(Embedding embedding, TextSegment segment) {
            added.add(segment);
            return "id";
        }

        @Override
        public List<String> addAll(List<Embedding> embeddings) {
            return List.of();
        }

        @Override
        public EmbeddingSearchResult<TextSegment> search(EmbeddingSearchRequest request) {
            return new EmbeddingSearchResult<>(List.of());
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class PrincipalConfig {
        @Bean
        SwitchablePrincipal currentPrincipal() {
            return new SwitchablePrincipal();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class StoreConfig {
        final RecordingEmbeddingStore rawEmbeddingStore = new RecordingEmbeddingStore();

        @Bean
        ChatMemoryStore chatMemoryStore() {
            return new InMemoryChatMemoryStore();
        }

        @Bean
        EmbeddingStore<TextSegment> embeddingStore() {
            return rawEmbeddingStore;
        }

        @Bean
        ContentRetriever contentRetriever() {
            return query -> List.of(
                    Content.from(TextSegment.from("a's doc", Metadata.from("casehub_tenant_id", "a"))),
                    Content.from(TextSegment.from("b's doc", Metadata.from("casehub_tenant_id", "b"))));
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class AlreadyWrappedConfig {
        final TenantIsolatingChatMemoryStore wrapped =
                new TenantIsolatingChatMemoryStore(new InMemoryChatMemoryStore(), null);

        @Bean
        ChatMemoryStore chatMemoryStore() {
            return wrapped;
        }
    }

    @Test
    void postProcessor_isOrderedEarly() {
        runner.run(context -> {
            Object processor = context.getBean("tenantIsolatingBeanPostProcessor");
            assertThat(processor).isInstanceOf(org.springframework.core.Ordered.class);
            assertThat(((org.springframework.core.Ordered) processor).getOrder())
                    .isEqualTo(org.springframework.core.Ordered.HIGHEST_PRECEDENCE + 100);
        });
    }
}
