package io.casehub.langchain4j.hybridsearch.spring;

import dev.langchain4j.rag.content.retriever.ContentRetriever;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnClass(ContentRetriever.class)
public class HybridSearchAutoConfiguration {
    // TODO: register CasehubHybridContentRetriever bean
}
