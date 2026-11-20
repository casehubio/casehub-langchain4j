package io.casehub.langchain4j.governance.gate;

import io.casehub.api.spi.ActionRiskClassifier;
import io.casehub.api.spi.ClassificationContext;
import io.casehub.api.spi.RiskClassifier;
import io.casehub.api.spi.RiskDecision;
import io.casehub.api.spi.routing.StaticSetStrategy;
import io.casehub.worker.api.PlannedAction;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Singleton;
import java.time.Duration;

/**
 * A classifier declared the usual way — a class bean, nothing injects it — and shipped in a
 * dependency jar, so ArC treats it as removable. Used to prove the gate still finds it.
 */
@RiskClassifier
@Alternative
@Singleton
public class DependencyRiskClassifier implements ActionRiskClassifier {

    @Override
    public RiskDecision classify(PlannedAction action, ClassificationContext context) {
        return action.actionType().equals("account.freeze")
                ? new RiskDecision.GateRequired("Account freeze", false, StaticSetStrategy.of("mlro"),
                        Duration.ofHours(24), null, null, null)
                : new RiskDecision.Autonomous();
    }
}
