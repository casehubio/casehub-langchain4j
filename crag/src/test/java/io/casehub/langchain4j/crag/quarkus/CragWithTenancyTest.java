package io.casehub.langchain4j.crag.quarkus;

import static org.assertj.core.api.Assertions.assertThat;

import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import io.casehub.langchain4j.crag.CragContentRetriever;
import io.casehub.langchain4j.tenancy.TenantIsolatingContentRetriever;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** CRAG wraps the tenancy wrapper: the evaluator never sees another tenant's content. */
@QuarkusTest
@TestProfile(CragWithTenancyTest.Profile.class)
class CragWithTenancyTest {

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Set<Class<?>> getEnabledAlternatives() {
            return Set.of(CragTestBeans.FakeEvaluator.class, CragTestBeans.TenantA.class);
        }
    }

    @Inject
    ContentRetriever retriever;

    @Test
    void cragWrapsTenancy_evaluatorSeesOnlyCurrentTenant() {
        CragTestBeans.EVALUATED.clear();

        assertThat(retriever.retrieve(Query.from("q"))).extracting(c -> c.textSegment().text())
                .containsExactly("good-a");
        assertThat(CragTestBeans.EVALUATED).containsExactly("good-a", "bad-a");

        Object outer = Unwrap.all(retriever);
        assertThat(outer).isInstanceOf(CragContentRetriever.class);
        assertThat(Unwrap.all(((CragContentRetriever) outer).delegate()))
                .isInstanceOf(TenantIsolatingContentRetriever.class);
    }
}
