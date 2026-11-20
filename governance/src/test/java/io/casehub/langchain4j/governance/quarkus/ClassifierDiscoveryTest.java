package io.casehub.langchain4j.governance.quarkus;

import static org.assertj.core.api.Assertions.assertThat;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import io.casehub.langchain4j.governance.gate.AccountTools;
import io.casehub.langchain4j.governance.gate.DependencyRiskClassifier;
import io.casehub.langchain4j.governance.gate.OversightToolGate;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * A class-style {@code @RiskClassifier} bean from a dependency jar, injected by nothing else, must
 * still be found: if ArC removed it, the gate would silently let every call through.
 */
@QuarkusTest
@TestProfile(ClassifierDiscoveryTest.Profile.class)
class ClassifierDiscoveryTest {

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Set<Class<?>> getEnabledAlternatives() {
            return Set.of(DependencyRiskClassifier.class, WorkOnlyTestBeans.class, TestTrustBeans.class);
        }

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "quarkus.index-dependency.gctests.group-id", "io.casehub.langchain4j",
                    "quarkus.index-dependency.gctests.artifact-id", "casehub-langchain4j-governance-core",
                    "quarkus.index-dependency.gctests.classifier", "tests");
        }
    }

    @Inject
    OversightToolGate gate;

    @Test
    void dependencyClassifierBean_isNotRemoved_gatedCallIsRefused() {
        AccountTools tools = new AccountTools();
        String result = gate.forAgent("treasurer").wrap(tools).entrySet().stream()
                .filter(e -> e.getKey().name().equals("freezeAccount")).findFirst().orElseThrow().getValue()
                .execute(ToolExecutionRequest.builder().id("1").name("freezeAccount")
                        .arguments("{\"accountId\":\"A1\"}").build(), "m1");

        assertThat(result).startsWith("NOT PERFORMED: ");
        assertThat(tools.freezes).hasValue(0);
    }
}
