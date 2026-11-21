package io.casehub.langchain4j.tenancy.quarkus;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.enterprise.inject.Produces;
import java.util.List;

/**
 * Stands in for another decorating module (such as crag) whose alternative outranks tenancy's.
 * Enabled only by {@link HigherPriorityDecoratorTest}.
 */
@Alternative
@ApplicationScoped
public class OuterDecoratorBeans {

    @Produces
    @Alternative
    @Priority(200)
    @ApplicationScoped
    ContentRetriever outerDecorator() {
        return query -> List.of(Content.from(TextSegment.from("outer", Metadata.from("casehub_tenant_id", "a"))));
    }
}
