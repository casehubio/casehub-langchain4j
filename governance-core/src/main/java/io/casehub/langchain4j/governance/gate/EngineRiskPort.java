package io.casehub.langchain4j.governance.gate;

import io.casehub.api.spi.ActionRiskClassifier;
import io.casehub.api.spi.ClassificationContext;
import io.casehub.api.spi.RiskDecision;
import io.casehub.api.spi.routing.StaticSetStrategy;
import io.casehub.worker.api.PlannedAction;
import java.util.Objects;
import java.util.Set;

/**
 * {@link RiskPort} backed by engine's {@link ActionRiskClassifier} — the same risk rules engine
 * applies to case workers. Pass the combined chain of {@code @RiskClassifier} beans.
 *
 * <p>A gate is only routable when it names a fixed, non-empty set of approver groups and needs a
 * single approver; anything else is {@link RiskVerdict.Unroutable}, so the call is refused rather
 * than sent to an approval request anyone could approve.
 */
public class EngineRiskPort implements RiskPort {

    private final ActionRiskClassifier classifier;

    public EngineRiskPort(ActionRiskClassifier classifier) {
        this.classifier = Objects.requireNonNull(classifier, "classifier required");
    }

    @Override
    public RiskVerdict classify(GatedToolCall call) {
        PlannedAction action = PlannedAction.of(
                "Agent '" + call.agentName() + "' calls tool '" + call.toolName() + "'",
                call.actionType(),
                call.arguments());
        ClassificationContext context =
                new ClassificationContext(call.agentName(), null, call.tenancyId(), null, call.toolName(), null);
        RiskDecision decision = classifier.classify(action, context);
        if (!(decision instanceof RiskDecision.GateRequired gate)) {
            return new RiskVerdict.Autonomous();
        }
        if (gate.quorum() != null) {
            return new RiskVerdict.Unroutable("the rule needs several approvers, which agent tool gates cannot route");
        }
        if (!(gate.candidateGroups() instanceof StaticSetStrategy groups) || groups.values().isEmpty()) {
            return new RiskVerdict.Unroutable("the rule names no fixed approver group");
        }
        return new RiskVerdict.Gate(
                gate.reason(), gate.reversible(), Set.copyOf(groups.values()), gate.expiresIn(), gate.scope());
    }
}
