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
import io.casehub.langchain4j.governance.gate.HttpWorkItemGateway;
import io.casehub.langchain4j.governance.gate.WorkItemApprovalPort;
import io.casehub.langchain4j.governance.gate.WorkTokenProvider;
import io.casehub.work.api.spi.WorkItemApi;
import java.net.URI;
import java.time.Duration;
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
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
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

    /** Base URL of a remote casehub-work service for approvals, used when there is no {@code WorkItemApi} bean. */
    public static final String WORK_URL = "casehub.langchain4j.governance.work.url";
    /** Bearer token for the remote casehub-work service; its principal belongs to {@link #WORK_TENANCY}. */
    public static final String WORK_TOKEN = "casehub.langchain4j.governance.work.token";
    /** The tenant {@link #WORK_TOKEN} belongs to; calls for other tenants are refused. */
    public static final String WORK_TENANCY = "casehub.langchain4j.governance.work.tenancy-id";
    /** Per-request timeout for the remote casehub-work service (default 10s). */
    public static final String WORK_TIMEOUT = "casehub.langchain4j.governance.work.timeout";

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
     * The tool oversight gate. Gated calls need {@code @RiskClassifier ActionRiskClassifier} beans, a
     * {@code CurrentPrincipal} and somewhere to store approval requests: a {@code WorkItemApi} bean
     * (in-process casehub-work), or else a remote casehub-work service at {@value #WORK_URL}, called with
     * the token for the call's tenant ({@value #WORK_TOKEN} for {@value #WORK_TENANCY}, or a
     * {@link WorkTokenProvider} bean for several tenants). Without either, every call that needs
     * approval is refused.
     */
    @Bean
    @ConditionalOnMissingBean
    public OversightToolGate oversightToolGate(
            AuditEntryWriter writer, ObjectProvider<CasehubAgentListener> lineage, BeanFactory beanFactory,
            Environment environment) {
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
                ? WorkSupport.approvalPort(beanFactory, environment)
                : null;
        if (approvals == null) {
            LOG.warnf("CaseHub oversight gate: no casehub-work WorkItemApi bean and no %s — tool calls that need"
                    + " approval are refused", WORK_URL);
        }
        Supplier<String> tenancy = ClassUtils.isPresent(CURRENT_PRINCIPAL, classLoader)
                ? PrincipalSupport.tenancy(beanFactory)
                : () -> null;
        if (!(ClassUtils.isPresent(CURRENT_PRINCIPAL, classLoader) && PrincipalSupport.available(beanFactory))) {
            LOG.warn("CaseHub oversight gate: no CurrentPrincipal bean — tool calls that need approval are refused");
        }
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

        static ApprovalPort approvalPort(BeanFactory beanFactory, Environment environment) {
            WorkItemApi api = beanFactory.getBeanProvider(WorkItemApi.class).getIfAvailable();
            if (api != null) {
                LOG.info("CaseHub oversight gate: approvals through the in-process casehub-work WorkItemApi");
                return new WorkItemApprovalPort(api);
            }
            String url = environment.getProperty(WORK_URL);
            if (url == null || url.isBlank()) {
                return null;
            }
            URI base = remoteUrl(url.trim());
            WorkTokenProvider tokens = beanFactory.getBeanProvider(WorkTokenProvider.class).getIfAvailable();
            if (tokens == null) {
                String token = environment.getProperty(WORK_TOKEN);
                String tenancy = environment.getProperty(WORK_TENANCY);
                if (token == null || token.isBlank() || tenancy == null || tenancy.isBlank()) {
                    throw new IllegalStateException(WORK_URL + " is set but there is no token for it: set " + WORK_TOKEN
                            + " and " + WORK_TENANCY + ", or define a WorkTokenProvider bean");
                }
                tokens = WorkTokenProvider.single(tenancy.trim(), token.trim());
            }
            Duration timeout = Binder.get(environment).bind(WORK_TIMEOUT, Duration.class)
                    .orElse(Duration.ofSeconds(10));
            LOG.infof("CaseHub oversight gate: approvals through the remote casehub-work service at %s", base.getHost());
            return new WorkItemApprovalPort(new HttpWorkItemGateway(base, tokens, timeout));
        }

        private static URI remoteUrl(String url) {
            URI uri;
            try {
                uri = URI.create(url);
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException(WORK_URL + " is not a valid URL: " + url, e);
            }
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null) {
                throw new IllegalStateException(WORK_URL + " must be an http(s) URL with a host — got " + url);
            }
            return uri;
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

        static boolean available(BeanFactory beanFactory) {
            return beanFactory.getBeanProvider(CurrentPrincipal.class).getIfAvailable() != null;
        }

        static Supplier<String> tenancy(BeanFactory beanFactory) {
            CurrentPrincipal principal = beanFactory.getBeanProvider(CurrentPrincipal.class).getIfAvailable();
            return principal == null ? () -> null : principal::tenancyId;
        }
    }
}
