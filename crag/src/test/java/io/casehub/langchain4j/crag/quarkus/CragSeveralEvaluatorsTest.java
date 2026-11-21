package io.casehub.langchain4j.crag.quarkus;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Two evaluators is a misconfiguration: fail clearly, never pass content through as "no evaluator". */
@QuarkusTest
@TestProfile(CragSeveralEvaluatorsTest.Profile.class)
class CragSeveralEvaluatorsTest {

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Set<Class<?>> getEnabledAlternatives() {
            return Set.of(CragTestBeans.FakeEvaluator.class, CragTestBeans.SecondEvaluator.class);
        }
    }

    @Inject
    ContentRetriever retriever;

    @Test
    void severalEvaluators_failWithClearMessage() {
        assertThatThrownBy(() -> retriever.retrieve(Query.from("q")))
                .hasStackTraceContaining("several RelevanceEvaluator beans");
    }
}
