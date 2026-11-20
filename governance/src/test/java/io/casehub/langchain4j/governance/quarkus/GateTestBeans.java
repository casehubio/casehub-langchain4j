package io.casehub.langchain4j.governance.quarkus;

import io.casehub.api.spi.ActionRiskClassifier;
import io.casehub.api.spi.RiskClassifier;
import io.casehub.api.spi.RiskDecision;
import io.casehub.api.spi.routing.StaticSetStrategy;
import io.casehub.langchain4j.governance.gate.FakeWorkItemApi;
import io.casehub.work.api.spi.WorkItemApi;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import java.time.Duration;

/** Enabled only by {@link OversightToolGateFullTest.GateProfile}. */
@Alternative
@ApplicationScoped
public class GateTestBeans {

    public static final FakeWorkItemApi WORK = new FakeWorkItemApi();

    @Produces
    @Singleton
    @RiskClassifier
    ActionRiskClassifier freezeNeedsApproval() {
        return (action, context) -> action.actionType().equals("account.freeze")
                ? new RiskDecision.GateRequired("Account freeze", false, StaticSetStrategy.of("mlro"),
                        Duration.ofHours(24), null, null, null)
                : new RiskDecision.Autonomous();
    }

    @Produces
    @Singleton
    WorkItemApi workItemApi() {
        return WORK.api;
    }
}
