package io.casehub.langchain4j.governance.quarkus;

import static org.assertj.core.api.Assertions.assertThat;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import io.casehub.langchain4j.governance.gate.AccountTools;
import io.casehub.langchain4j.governance.gate.OversightToolGate;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

@QuarkusTest
class OversightToolGateBeansTest {

    @Inject
    OversightToolGate gate;

    @Test
    void noClassifiers_gateIsPassThrough() {
        AccountTools tools = new AccountTools();
        String result = gate.forAgent("treasurer").wrap(tools).entrySet().stream()
                .filter(e -> e.getKey().name().equals("freezeAccount")).findFirst().orElseThrow().getValue()
                .execute(ToolExecutionRequest.builder().id("1").name("freezeAccount")
                        .arguments("{\"accountId\":\"A1\"}").build(), "m1");

        assertThat(result).isEqualTo("frozen A1");
        assertThat(tools.freezes).hasValue(1);
    }
}
