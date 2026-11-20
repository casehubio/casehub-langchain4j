package io.casehub.langchain4j.governance.quarkus;

import io.casehub.api.spi.ActionRiskClassifier;
import io.casehub.api.spi.ChainedActionRiskClassifier;
import io.casehub.api.spi.RiskClassifier;
import io.casehub.langchain4j.audit.AuditEntryWriter;
import io.casehub.langchain4j.audit.CasehubAgentListener;
import io.casehub.langchain4j.governance.GovernanceAgentListener;
import io.casehub.langchain4j.governance.LedgerTrustPort;
import io.casehub.langchain4j.governance.TrustPort;
import io.casehub.langchain4j.governance.gate.ApprovalPort;
import io.casehub.langchain4j.governance.gate.EngineRiskPort;
import io.casehub.langchain4j.governance.gate.OversightToolGate;
import io.casehub.langchain4j.governance.gate.RiskPort;
import io.casehub.langchain4j.governance.gate.WorkItemApprovalPort;
import io.casehub.ledger.api.spi.OutcomeRecorder;
import io.casehub.ledger.api.spi.TrustScoreSource;
import io.casehub.platform.api.identity.CurrentPrincipal;
import io.casehub.work.api.spi.WorkItemApi;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import java.util.List;
import java.util.function.Supplier;
import org.eclipse.microprofile.config.ConfigProvider;
import org.jboss.logging.Logger;

/**
 * Produces the governance beans for Quarkus:
 *
 * <ul>
 *   <li>{@link GovernanceAgentListener} replaces the audit module's default agent listener, so an
 *       application gets one listener that records lineage and trust. Trust is active when
 *       {@code TrustScoreSource} and {@code OutcomeRecorder} beans exist; outcomes also need a
 *       {@code CurrentPrincipal} to supply the tenant.
 *   <li>{@link OversightToolGate} gates wrapped tool calls with the {@code @RiskClassifier} beans and
 *       casehub-work approvals.
 * </ul>
 *
 * <p>Every CaseHub bean is <em>injected</em>, never looked up: ArC removes beans no injection point
 * uses, so a programmatic lookup would miss class-style beans and the module would silently degrade.
 */
@ApplicationScoped
public class GovernanceBeans {

    /** Weight of one agent invocation as trust evidence, in {@code (0.0, 1.0]}. */
    public static final String OUTCOME_CONFIDENCE = "casehub.langchain4j.governance.outcome-confidence";

    private static final Logger LOG = Logger.getLogger(GovernanceBeans.class);

    @Produces
    @Singleton
    public GovernanceAgentListener governanceAgentListener(AuditEntryWriter writer, Instance<TrustScoreSource> source,
            Instance<OutcomeRecorder> recorder, Instance<CurrentPrincipal> principal) {
        double confidence = ConfigProvider.getConfig()
                .getOptionalValue(OUTCOME_CONFIDENCE, Double.class)
                .orElse(LedgerTrustPort.DEFAULT_CONFIDENCE);
        if (!(confidence > 0.0 && confidence <= 1.0)) {
            throw new IllegalArgumentException(OUTCOME_CONFIDENCE + " must be in (0.0, 1.0] — got " + confidence);
        }
        TrustPort trust = source.isResolvable() && recorder.isResolvable()
                ? new LedgerTrustPort(source.get(), recorder.get(), confidence)
                : null;
        LOG.infof("CaseHub governance: agent lineage %s",
                trust == null ? "only — no trust system available" : "and trust outcomes");
        return new GovernanceAgentListener(writer, trust, tenancy(principal));
    }

    /**
     * The tool oversight gate. Gated calls need {@code @RiskClassifier ActionRiskClassifier} beans,
     * a {@code WorkItemApi} bean and a {@code CurrentPrincipal}; without classifiers every call runs,
     * and without the others every call that needs approval is refused.
     */
    @Produces
    @Singleton
    public OversightToolGate oversightToolGate(AuditEntryWriter writer, CasehubAgentListener lineage,
            @RiskClassifier Instance<ActionRiskClassifier> classifiers, Instance<WorkItemApi> workItems,
            Instance<CurrentPrincipal> principal) {
        List<ActionRiskClassifier> found = classifiers.stream().toList();
        if (found.isEmpty()) {
            LOG.warn("CaseHub oversight gate: no @RiskClassifier ActionRiskClassifier beans — wrapped tool calls"
                    + " run without approval");
            return new OversightToolGate(null, null, () -> null, writer, lineage);
        }
        RiskPort risk = new EngineRiskPort(new ChainedActionRiskClassifier(found));
        ApprovalPort approvals = workItems.isResolvable() ? new WorkItemApprovalPort(workItems.get()) : null;
        if (approvals == null) {
            LOG.warn("CaseHub oversight gate: no casehub-work WorkItemApi bean — tool calls that need approval"
                    + " are refused");
        }
        if (!principal.isResolvable()) {
            LOG.warn("CaseHub oversight gate: no CurrentPrincipal bean — tool calls that need approval are refused");
        }
        return new OversightToolGate(risk, approvals, tenancy(principal), writer, lineage);
    }

    private static Supplier<String> tenancy(Instance<CurrentPrincipal> principal) {
        if (!principal.isResolvable()) {
            return () -> null;
        }
        CurrentPrincipal current = principal.get();
        return current::tenancyId;
    }
}
