package io.casehub.langchain4j.crag.quarkus;

import static org.assertj.core.api.Assertions.assertThat;

import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import io.casehub.langchain4j.tenancy.TenantIsolatingContentRetriever;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Without an evaluator the application still gets the tenancy wrapper, ungraded. */
@QuarkusTest
@TestProfile(CragWithoutEvaluatorTest.Profile.class)
class CragWithoutEvaluatorTest {

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Set<Class<?>> getEnabledAlternatives() {
            return Set.of(CragTestBeans.TenantA.class);
        }
    }

    @Inject
    ContentRetriever retriever;

    @Test
    void noEvaluator_tenancyWrapperPassesThrough() {
        assertThat(Unwrap.all(retriever)).isInstanceOf(TenantIsolatingContentRetriever.class);
        assertThat(retriever.retrieve(Query.from("q"))).extracting(c -> c.textSegment().text())
                .containsExactly("good-a", "bad-a");
    }
}
