package io.casehub.langchain4j.governance.spring;

import dev.langchain4j.agentic.observability.AgentListener;
import io.casehub.langchain4j.audit.AuditEntryWriter;
import io.casehub.api.spi.ActionRiskClassifier;
import io.casehub.api.spi.ChainedActionRiskClassifier;
import io.casehub.api.spi.RiskClassifier;
import io.casehub.langchain4j.audit.CasehubAgentListener;
import io.casehub.langchain4j.governance.gate.ApprovalPort;
import io.casehub.langchain4j.governance.gate.EngineRiskPort;
import io.casehub.langchain4j.governance.gate.OversightToolGate;
import io.casehub.langchain4j.governance.gate.RiskPort;
import io.casehub.langchain4j.governance.gate.WorkItemApprovalPort;
import io.casehub.work.api.spi.WorkItemApi;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import io.casehub.langchain4j.audit.spring.AuditAutoConfiguration;
import io.casehub.langchain4j.governance.GovernanceAgentListener;
import io.casehub.langchain4j.governance.LedgerTrustPort;
import io.casehub.langchain4j.governance.TrustPort;
import io.casehub.ledger.api.spi.OutcomeRecorder;
import io.casehub.ledger.api.spi.TrustScoreSource;
import io.casehub.platform.api.identity.CurrentPrincipal;
import java.util.function.Supplier;
import org.jboss.logging.Logger;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.util.ClassUtils;

/**
 * Replaces the audit module's agent listener with {@link GovernanceAgentListener}, so an
 * application gets one listener that records lineage and trust.
 *
 * <p>Trust is active when {@code TrustScoreSource} and {@code OutcomeRecorder} beans exist;
 * without them the listener records lineage only. Outcomes also need a {@code CurrentPrincipal}
 * bean to supply the tenant.
 */
@AutoConfiguration(before = AuditAutoConfiguration.class)
@ConditionalOnClass(AgentListener.class)
public class GovernanceAutoConfiguration {

    /** Weight of one agent invocation as trust evidence, in {@code (0.0, 1.0]}. */
    public static final String OUTCOME_CONFIDENCE = "casehub.langchain4j.governance.outcome-confidence";

    private static final Logger LOG = Logger.getLogger(GovernanceAutoConfiguration.class);
    private static final String TRUST_SOURCE = "io.casehub.ledger.api.spi.TrustScoreSource";
    private static final String RISK_CLASSIFIER = "io.casehub.api.spi.ActionRiskClassifier";
    private static final String WORK_ITEM_API = "io.casehub.work.api.spi.WorkItemApi";
    private static final String CURRENT_PRINCIPAL = "io.casehub.platform.api.identity.CurrentPrincipal";

    @Bean
    @ConditionalOnMissingBean
    public GovernanceAgentListener governanceAgentListener(
            AuditEntryWriter writer,
            BeanFactory beanFactory,
            @Value("${" + OUTCOME_CONFIDENCE + ":" + LedgerTrustPort.DEFAULT_CONFIDENCE + "}") double confidence) {
        if (!(confidence > 0.0 && confidence <= 1.0)) {
            throw new IllegalArgumentException(OUTCOME_CONFIDENCE + " must be in (0.0, 1.0] — got " + confidence);
        }
        ClassLoader classLoader = GovernanceAutoConfiguration.class.getClassLoader();
        TrustPort trust = ClassUtils.isPresent(TRUST_SOURCE, classLoader)
                ? LedgerSupport.trustPort(beanFactory, confidence)
                : null;
        Supplier<String> tenancy = ClassUtils.isPresent(CURRENT_PRINCIPAL, classLoader)
                ? PrincipalSupport.tenancy(beanFactory)
                : () -> null;
        LOG.infof("CaseHub governance: agent lineage %s", trust == null ? "only — no trust system available" : "and trust outcomes");
        return new GovernanceAgentListener(writer, trust, tenancy);
    }

    /**
     * The tool oversight gate. Gated calls need {@code @RiskClassifier ActionRiskClassifier} beans,
     * a {@code WorkItemApi} bean and a {@code CurrentPrincipal}. casehub-work ships no Spring
     * {@code WorkItemApi} yet (casehubio/casehub-langchain4j#15), so unless the application provides
     * one, every call that needs approval is refused.
     */
    @Bean
    @ConditionalOnMissingBean
    public OversightToolGate oversightToolGate(
            AuditEntryWriter writer, ObjectProvider<CasehubAgentListener> lineage, BeanFactory beanFactory) {
        ClassLoader classLoader = GovernanceAutoConfiguration.class.getClassLoader();
        CasehubAgentListener listener = lineage.getIfAvailable();
        RiskPort risk = ClassUtils.isPresent(RISK_CLASSIFIER, classLoader) && beanFactory instanceof ListableBeanFactory beans
                ? EngineSupport.riskPort(beans)
                : null;
        if (risk == null) {
            LOG.info("CaseHub oversight gate: no @RiskClassifier beans — wrapped tool calls run without approval");
            return new OversightToolGate(null, null, () -> null, writer, listener);
        }
        ApprovalPort approvals = ClassUtils.isPresent(WORK_ITEM_API, classLoader)
                ? WorkSupport.approvalPort(beanFactory)
                : null;
        if (approvals == null) {
            LOG.warn("CaseHub oversight gate: no casehub-work WorkItemApi bean — tool calls that need approval"
                    + " are refused (Spring support: casehubio/casehub-langchain4j#15)");
        }
        Supplier<String> tenancy = ClassUtils.isPresent(CURRENT_PRINCIPAL, classLoader)
                ? PrincipalSupport.tenancy(beanFactory)
                : () -> null;
        return new OversightToolGate(risk, approvals, tenancy, writer, listener);
    }

    /** Touches the optional engine types; loaded only when they are present. */
    private static final class EngineSupport {

        static RiskPort riskPort(ListableBeanFactory beans) {
            List<ActionRiskClassifier> classifiers = new ArrayList<>();
            for (String name : beans.getBeanNamesForType(ActionRiskClassifier.class)) {
                if (beans.findAnnotationOnBean(name, RiskClassifier.class) != null) {
                    classifiers.add(beans.getBean(name, ActionRiskClassifier.class));
                }
            }
            return classifiers.isEmpty() ? null : new EngineRiskPort(new ChainedActionRiskClassifier(classifiers));
        }
    }

    /** Touches the optional casehub-work types; loaded only when they are present. */
    private static final class WorkSupport {

        static ApprovalPort approvalPort(BeanFactory beanFactory) {
            WorkItemApi api = beanFactory.getBeanProvider(WorkItemApi.class).getIfAvailable();
            return api == null ? null : new WorkItemApprovalPort(api);
        }
    }

    /** Touches the optional ledger types; loaded only when they are present. */
    private static final class LedgerSupport {

        static TrustPort trustPort(BeanFactory beanFactory, double confidence) {
            TrustScoreSource source = beanFactory.getBeanProvider(TrustScoreSource.class).getIfAvailable();
            OutcomeRecorder recorder = beanFactory.getBeanProvider(OutcomeRecorder.class).getIfAvailable();
            return source == null || recorder == null ? null : new LedgerTrustPort(source, recorder, confidence);
        }
    }

    /** Touches the optional identity type; loaded only when it is present. */
    private static final class PrincipalSupport {

        static Supplier<String> tenancy(BeanFactory beanFactory) {
            CurrentPrincipal principal = beanFactory.getBeanProvider(CurrentPrincipal.class).getIfAvailable();
            return principal == null ? () -> null : principal::tenancyId;
        }
    }
}
