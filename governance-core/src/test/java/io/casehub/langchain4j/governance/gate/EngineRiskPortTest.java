package io.casehub.langchain4j.governance.gate;

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.api.spi.ActionRiskClassifier;
import io.casehub.api.spi.ClassificationContext;
import io.casehub.api.spi.QuorumConfig;
import io.casehub.api.spi.RiskDecision;
import io.casehub.api.spi.routing.CandidateSetStrategy;
import io.casehub.api.spi.routing.StaticSetStrategy;
import io.casehub.worker.api.PlannedAction;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class EngineRiskPortTest {

    private final GatedToolCall call =
            new GatedToolCall("treasurer", "freezeAccount", "account.freeze", Map.of("accountId", "A1"), "acme");

    private static RiskDecision.GateRequired gate(CandidateSetStrategy groups, QuorumConfig quorum) {
        return new RiskDecision.GateRequired("Account freeze", false, groups, Duration.ofHours(24), "aml", null, quorum);
    }

    private RiskVerdict classify(RiskDecision decision) {
        return new EngineRiskPort((action, context) -> decision).classify(call);
    }

    @Test
    void buildsPlannedActionAndContext() {
        AtomicReference<PlannedAction> action = new AtomicReference<>();
        AtomicReference<ClassificationContext> context = new AtomicReference<>();
        ActionRiskClassifier capturing = (a, c) -> {
            action.set(a);
            context.set(c);
            return new RiskDecision.Autonomous();
        };

        new EngineRiskPort(capturing).classify(call);

        assertThat(action.get().description()).isEqualTo("Agent 'treasurer' calls tool 'freezeAccount'");
        assertThat(action.get().actionType()).isEqualTo("account.freeze");
        assertThat(action.get().parameters()).containsExactly(Map.entry("accountId", "A1"));
        assertThat(context.get()).isEqualTo(
                new ClassificationContext("treasurer", null, "acme", null, "freezeAccount", null));
    }

    @Test
    void autonomous_mapsToAutonomous() {
        assertThat(classify(new RiskDecision.Autonomous())).isInstanceOf(RiskVerdict.Autonomous.class);
    }

    @Test
    void staticGroups_mapToGate() {
        assertThat(classify(gate(StaticSetStrategy.of("mlro"), null))).isEqualTo(
                new RiskVerdict.Gate("Account freeze", false, Set.of("mlro"), Duration.ofHours(24), "aml"));
    }

    @Test
    void quorum_isUnroutable() {
        assertThat(classify(gate(StaticSetStrategy.of("mlro"), new QuorumConfig(3, 2, null, false))))
                .isInstanceOf(RiskVerdict.Unroutable.class);
    }

    @Test
    void nullGroups_isUnroutable() {
        assertThat(classify(gate(null, null))).isInstanceOf(RiskVerdict.Unroutable.class);
    }

    @Test
    void nonStaticStrategy_isUnroutable() {
        assertThat(classify(gate(Mockito.mock(CandidateSetStrategy.class), null)))
                .isInstanceOf(RiskVerdict.Unroutable.class);
    }

    @Test
    void emptyStaticGroups_isUnroutable() {
        assertThat(classify(gate(StaticSetStrategy.of(), null))).isInstanceOf(RiskVerdict.Unroutable.class);
    }
}
