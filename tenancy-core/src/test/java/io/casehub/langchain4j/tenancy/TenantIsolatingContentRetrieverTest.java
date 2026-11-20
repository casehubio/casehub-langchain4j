package io.casehub.langchain4j.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import java.util.List;
import org.junit.jupiter.api.Test;

class TenantIsolatingContentRetrieverTest {

    private static final String KEY = "casehub_tenant_id";

    private final MutablePrincipal principal = new MutablePrincipal("a");
    private final List<Content> all = List.of(
            Content.from(TextSegment.from("a's doc", Metadata.from(KEY, "a"))),
            Content.from(TextSegment.from("b's doc", Metadata.from(KEY, "b"))),
            Content.from(TextSegment.from("untagged doc")));
    private final ContentRetriever delegate = query -> all;
    private final TenantIsolatingContentRetriever retriever = new TenantIsolatingContentRetriever(delegate, principal);

    private List<String> texts(ContentRetriever r) {
        return r.retrieve(Query.from("q")).stream().map(c -> c.textSegment().text()).toList();
    }

    @Test
    void filtersOutOtherTenantsContent() {
        assertThat(texts(retriever)).containsExactly("a's doc");
        principal.tenant = "b";
        assertThat(texts(retriever)).containsExactly("b's doc");
    }

    @Test
    void dropsContentWithoutTenantKey() {
        assertThat(texts(retriever)).doesNotContain("untagged doc");
    }

    @Test
    void nullPrincipal_passesThrough() {
        assertThat(texts(new TenantIsolatingContentRetriever(delegate, null)))
                .containsExactly("a's doc", "b's doc", "untagged doc");
    }

    @Test
    void blankTenant_throws() {
        principal.tenant = null;

        assertThatThrownBy(() -> retriever.retrieve(Query.from("q")))
                .isInstanceOf(TenantContextMissingException.class);
    }
}
