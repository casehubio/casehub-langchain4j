package io.casehub.langchain4j.governance.quarkus;

import io.casehub.langchain4j.audit.AuditEntryWriter;
import io.casehub.langchain4j.governance.GovernanceAgentListener;
import io.casehub.langchain4j.governance.LedgerTrustPort;
import io.casehub.langchain4j.governance.TrustPort;
import io.casehub.ledger.api.spi.OutcomeRecorder;
import io.casehub.ledger.api.spi.TrustScoreSource;
import io.casehub.platform.api.identity.CurrentPrincipal;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import java.util.function.Supplier;
import org.eclipse.microprofile.config.ConfigProvider;
import org.jboss.logging.Logger;

/**
 * Replaces the audit module's default agent listener with {@link GovernanceAgentListener}, so an
 * application gets one listener that records lineage and trust.
 *
 * <p>Trust is active when {@code TrustScoreSource} and {@code OutcomeRecorder} beans exist;
 * without them the listener records lineage only. Outcomes also need a {@code CurrentPrincipal}
 * bean to supply the tenant.
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
        // Injected, not looked up: ArC then keeps class-style beans nothing else injects.
        double confidence = ConfigProvider.getConfig()
                .getOptionalValue(OUTCOME_CONFIDENCE, Double.class)
                .orElse(LedgerTrustPort.DEFAULT_CONFIDENCE);
        if (!(confidence > 0.0 && confidence <= 1.0)) {
            throw new IllegalArgumentException(OUTCOME_CONFIDENCE + " must be in (0.0, 1.0] — got " + confidence);
        }
        TrustPort trust = source.isResolvable() && recorder.isResolvable()
                ? new LedgerTrustPort(source.get(), recorder.get(), confidence)
                : null;
        Supplier<String> tenancy = tenancy(principal);
        LOG.infof("CaseHub governance: agent lineage %s",
                trust == null ? "only — no trust system available" : "and trust outcomes");
        return new GovernanceAgentListener(writer, trust, tenancy);
    }

    private static Supplier<String> tenancy(Instance<CurrentPrincipal> principal) {
        if (!principal.isResolvable()) {
            return () -> null;
        }
        CurrentPrincipal current = principal.get();
        return current::tenancyId;
    }
}
