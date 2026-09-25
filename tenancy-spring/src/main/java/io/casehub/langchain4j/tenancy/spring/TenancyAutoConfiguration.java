package io.casehub.langchain4j.tenancy.spring;

import io.casehub.platform.api.identity.CurrentPrincipal;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnClass(CurrentPrincipal.class)
public class TenancyAutoConfiguration {

    // TODO: BeanPostProcessor that wraps ChatMemoryStore, EmbeddingStore,
    // and ContentRetriever beans with tenant-isolating decorators.
}
