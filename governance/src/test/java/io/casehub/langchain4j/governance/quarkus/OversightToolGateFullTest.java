package io.casehub.langchain4j.governance.quarkus;

import static org.assertj.core.api.Assertions.assertThat;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.service.tool.ToolExecutor;
import io.casehub.langchain4j.governance.gate.AccountTools;
import io.casehub.langchain4j.governance.gate.OversightToolGate;
import io.casehub.work.api.WorkItemStatus;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(OversightToolGateFullTest.GateProfile.class)
class OversightToolGateFullTest {

    public static class GateProfile implements QuarkusTestProfile {
        @Override
        public Set<Class<?>> getEnabledAlternatives() {
            return Set.of(GateTestBeans.class, TestTrustBeans.class);
        }
    }

    @Inject
    OversightToolGate gate;

    private static String freeze(Map<ToolSpecification, ToolExecutor> tools) {
        return tools.entrySet().stream().filter(e -> e.getKey().name().equals("freezeAccount"))
                .findFirst().orElseThrow().getValue()
                .execute(ToolExecutionRequest.builder().id("1").name("freezeAccount")
                        .arguments("{\"accountId\":\"A1\"}").build(), "m1");
    }

    @Test
    void gatedCall_refusedUntilApproved_thenRunsOnce() {
        AccountTools tools = new AccountTools();
        Map<ToolSpecification, ToolExecutor> wrapped = gate.forAgent("treasurer").wrap(tools);

        assertThat(freeze(wrapped)).startsWith("NOT PERFORMED: ");
        assertThat(GateTestBeans.WORK.created).hasSize(1);
        assertThat(GateTestBeans.WORK.created.get(0).tenancyId).isEqualTo("acme");

        GateTestBeans.WORK.items.keySet().forEach(id -> GateTestBeans.WORK.setStatus(id, WorkItemStatus.COMPLETED));

        assertThat(freeze(wrapped)).isEqualTo("frozen A1");
        assertThat(tools.freezes).hasValue(1);
    }
}
