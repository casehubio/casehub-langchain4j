package io.casehub.langchain4j.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import dev.langchain4j.store.memory.chat.InMemoryChatMemoryStore;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TenantIsolatingChatMemoryStoreTest {

    private final MutablePrincipal principal = new MutablePrincipal("a");
    private final InMemoryChatMemoryStore delegate = new InMemoryChatMemoryStore();
    private final TenantIsolatingChatMemoryStore store = new TenantIsolatingChatMemoryStore(delegate, principal);
    private final List<ChatMessage> messages = List.of(UserMessage.from("hello from a"));

    @Test
    void sameMemoryId_differentTenants_noLeakage() {
        store.updateMessages("m1", messages);

        principal.tenant = "b";
        assertThat(store.getMessages("m1")).isEmpty();

        principal.tenant = "a";
        assertThat(store.getMessages("m1")).isEqualTo(messages);
    }

    @Test
    void delete_onlyAffectsOwnTenant() {
        store.updateMessages("m1", messages);

        principal.tenant = "b";
        store.updateMessages("m1", List.of(UserMessage.from("hello from b")));
        store.deleteMessages("m1");
        assertThat(store.getMessages("m1")).isEmpty();

        principal.tenant = "a";
        assertThat(store.getMessages("m1")).isEqualTo(messages);
    }

    @Test
    void delegateSeesScopedId() {
        store.updateMessages("m1", messages);

        assertThat(delegate.getMessages("a::m1")).isEqualTo(messages);
        assertThat(delegate.getMessages("m1")).isEmpty();
    }

    @Test
    void nullPrincipal_passesThroughUnscoped() {
        List<Object> seen = new ArrayList<>();
        ChatMemoryStore recording = new ChatMemoryStore() {
            @Override
            public List<ChatMessage> getMessages(Object memoryId) {
                seen.add(memoryId);
                return List.of();
            }

            @Override
            public void updateMessages(Object memoryId, List<ChatMessage> msgs) {
                seen.add(memoryId);
            }

            @Override
            public void deleteMessages(Object memoryId) {
                seen.add(memoryId);
            }
        };
        TenantIsolatingChatMemoryStore passthrough = new TenantIsolatingChatMemoryStore(recording, null);
        Object memoryId = 42L;

        passthrough.getMessages(memoryId);
        passthrough.updateMessages(memoryId, messages);
        passthrough.deleteMessages(memoryId);

        assertThat(seen).containsExactly(memoryId, memoryId, memoryId);
    }

    @Test
    void blankTenant_throws() {
        principal.tenant = " ";

        assertThatThrownBy(() -> store.getMessages("m1")).isInstanceOf(TenantContextMissingException.class);
        assertThatThrownBy(() -> store.updateMessages("m1", messages))
                .isInstanceOf(TenantContextMissingException.class);
        assertThatThrownBy(() -> store.deleteMessages("m1")).isInstanceOf(TenantContextMissingException.class);
    }

    @Test
    void colonInMemoryId_cannotReachAnotherTenantsConversation() {
        principal.tenant = "a";
        store.updateMessages("x", messages);

        principal.tenant = "a:";
        assertThatThrownBy(() -> store.getMessages("x")).isInstanceOf(TenantContextMissingException.class);
        principal.tenant = "a";
        assertThat(store.getMessages(":x")).isEmpty();
    }
}
