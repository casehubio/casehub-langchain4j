package io.casehub.langchain4j.governance.quarkus;

import io.casehub.langchain4j.audit.AuditEntryWriter;
import io.casehub.langchain4j.governance.GovernanceAgentListener;
import io.casehub.langchain4j.governance.LedgerTrustPort;
import io.casehub.langchain4j.governance.TrustPort;
import io.casehub.ledger.api.spi.OutcomeRecorder;
import io.casehub.ledger.api.spi.TrustScoreSource;
import io.casehub.platform.api.identity.CurrentPrincipal;
import io.quarkus.arc.Arc;
import io.quarkus.arc.InstanceHandle;
import jakarta.enterprise.context.ApplicationScoped;
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
    private static final String TRUST_SOURCE = "io.casehub.ledger.api.spi.TrustScoreSource";
    private static final String CURRENT_PRINCIPAL = "io.casehub.platform.api.identity.CurrentPrincipal";

    @Produces
    @Singleton
    public GovernanceAgentListener governanceAgentListener(AuditEntryWriter writer) {
        double confidence = ConfigProvider.getConfig()
                .getOptionalValue(OUTCOME_CONFIDENCE, Double.class)
                .orElse(LedgerTrustPort.DEFAULT_CONFIDENCE);
        if (!(confidence > 0.0 && confidence <= 1.0)) {
            throw new IllegalArgumentException(OUTCOME_CONFIDENCE + " must be in (0.0, 1.0] — got " + confidence);
        }
        TrustPort trust = isPresent(TRUST_SOURCE) ? LedgerSupport.trustPort(confidence) : null;
        Supplier<String> tenancy = isPresent(CURRENT_PRINCIPAL) ? PrincipalSupport.tenancy() : () -> null;
        LOG.infof("CaseHub governance: agent lineage %s",
                trust == null ? "only — no trust system available" : "and trust outcomes");
        return new GovernanceAgentListener(writer, trust, tenancy);
    }

    private static boolean isPresent(String className) {
        try {
            Class.forName(className, false, Thread.currentThread().getContextClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    /** Touches the optional ledger types; loaded only when they are present. */
    private static final class LedgerSupport {

        static TrustPort trustPort(double confidence) {
            InstanceHandle<TrustScoreSource> source = Arc.container().instance(TrustScoreSource.class);
            InstanceHandle<OutcomeRecorder> recorder = Arc.container().instance(OutcomeRecorder.class);
            return source.isAvailable() && recorder.isAvailable()
                    ? new LedgerTrustPort(source.get(), recorder.get(), confidence)
                    : null;
        }
    }

    /** Touches the optional identity type; loaded only when it is present. */
    private static final class PrincipalSupport {

        static Supplier<String> tenancy() {
            InstanceHandle<CurrentPrincipal> principal = Arc.container().instance(CurrentPrincipal.class);
            if (!principal.isAvailable()) {
                return () -> null;
            }
            CurrentPrincipal current = principal.get();
            return current::tenancyId;
        }
    }
}
