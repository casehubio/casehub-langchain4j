package io.casehub.langchain4j.tenancy.quarkus;

import static org.assertj.core.api.Assertions.assertThat;

import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.inject.Inject;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Another module's decorator outranks tenancy's alternative. Tenancy must still wrap the
 * application's retriever — choosing the outer decorator would make the two wrap each other.
 */
@QuarkusTest
@TestProfile(HigherPriorityDecoratorTest.Profile.class)
class HigherPriorityDecoratorTest {

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Set<Class<?>> getEnabledAlternatives() {
            return Set.of(OuterDecoratorBeans.class);
        }
    }

    @Inject
    BeanManager beanManager;

    @Inject
    SwitchablePrincipal principal;

    @Test
    void tenancyDelegate_isNotTheHigherPriorityDecorator() {
        principal.tenant = "a";
        Bean<?> tenancyBean = beanManager.getBeans(ContentRetriever.class, Any.Literal.INSTANCE).stream()
                .filter(b -> b.getBeanClass() == TenancyBeans.class)
                .findFirst().orElseThrow();
        ContentRetriever tenancy = (ContentRetriever) beanManager.getReference(
                tenancyBean, ContentRetriever.class, beanManager.createCreationalContext(tenancyBean));

        assertThat(tenancy.retrieve(Query.from("q"))).extracting(c -> c.textSegment().text())
                .containsExactly("a's doc");
    }
}
