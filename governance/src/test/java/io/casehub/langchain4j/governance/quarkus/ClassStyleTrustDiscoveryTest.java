package io.casehub.langchain4j.governance.quarkus;

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.langchain4j.audit.CasehubAgentListener;
import io.casehub.langchain4j.audit.beans.ClassStyleCaseHubBeans;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Trust and identity beans declared as plain classes in a dependency jar, injected by nothing
 * else, must still be found; if ArC removed them, governance would silently record lineage only.
 */
@QuarkusTest
@TestProfile(ClassStyleTrustDiscoveryTest.Profile.class)
class ClassStyleTrustDiscoveryTest {

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Set<Class<?>> getEnabledAlternatives() {
            return Set.of(ClassStyleCaseHubBeans.Scores.class, ClassStyleCaseHubBeans.Outcomes.class,
                    ClassStyleCaseHubBeans.Principal.class);
        }

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "quarkus.index-dependency.acbeans.group-id", "io.casehub.langchain4j",
                    "quarkus.index-dependency.acbeans.artifact-id", "casehub-langchain4j-audit-core",
                    "quarkus.index-dependency.acbeans.classifier", "tests");
        }
    }

    @Inject
    CasehubAgentListener listener;

    @Test
    void classStyleTrustBeans_areNotRemoved_outcomeIsRecorded() {
        ClassStyleCaseHubBeans.OUTCOMES.clear();

        Invocations.invoke(listener);

        assertThat(ClassStyleCaseHubBeans.OUTCOMES).singleElement()
                .satisfies(o -> assertThat(o.actorId()).isEqualTo("agent:researcher"));
    }
}
