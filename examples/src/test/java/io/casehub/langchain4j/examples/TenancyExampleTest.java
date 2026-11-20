package io.casehub.langchain4j.examples;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

@QuarkusTest
class TenancyExampleTest {

    @Inject
    TenancyExample example;

    @Inject
    DemoPrincipal principal;

    @Test
    void twoTenants_sameConversationId_neverSeeEachOther() {
        principal.switchTo("acme");
        example.remember("conversation-1", "acme's secret");

        principal.switchTo("globex");
        assertThat(example.recall("conversation-1")).isEmpty();
        example.remember("conversation-1", "globex's secret");

        principal.switchTo("acme");
        assertThat(example.recall("conversation-1")).containsExactly("acme's secret");
    }
}
