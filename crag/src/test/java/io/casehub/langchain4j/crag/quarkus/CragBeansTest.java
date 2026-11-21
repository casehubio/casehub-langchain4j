package io.casehub.langchain4j.crag.quarkus;

import static org.assertj.core.api.Assertions.assertThat;

import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import io.casehub.langchain4j.crag.CragContentRetriever;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.Set;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(CragBeansTest.Profile.class)
class CragBeansTest {

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Set<Class<?>> getEnabledAlternatives() {
            return Set.of(CragTestBeans.FakeEvaluator.class);
        }
    }

    @Inject
    ContentRetriever retriever;

    @Test
    void injectedRetriever_dropsIncorrect_andPublishesQuality() {
        CragTestBeans.QUALITIES.clear();

        assertThat(Unwrap.all(retriever)).isInstanceOf(CragContentRetriever.class);
        assertThat(retriever.retrieve(Query.from("q"))).extracting(c -> c.textSegment().text())
                .containsExactly("good-a", "good-b");
        assertThat(CragTestBeans.QUALITIES).singleElement()
                .satisfies(q -> assertThat(q.quality().totalIncorrect()).isEqualTo(1));
    }
}
