package io.casehub.langchain4j.governance;

import dev.langchain4j.agentic.observability.AgentInvocationError;
import dev.langchain4j.agentic.observability.AgentRequest;
import dev.langchain4j.agentic.observability.AgentResponse;
import dev.langchain4j.agentic.scope.AgenticScope;
import io.casehub.langchain4j.audit.AuditEntryWriter;
import io.casehub.langchain4j.audit.CasehubAgentListener;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.function.Supplier;
import org.jboss.logging.Logger;

/**
 * Adds trust to the causal lineage recorded by {@link CasehubAgentListener}:
 *
 * <ul>
 *   <li>the agent's current trust score is recorded on its STARTED entry, and
 *   <li>every completed or failed invocation is reported to the trust system as an outcome.
 * </ul>
 *
 * <p>It extends the audit listener rather than running beside it, so an application using both
 * modules gets one lineage, not two.
 *
 * <p><b>This listener observes; it cannot veto.</b> LangChain4j catches every exception thrown by
 * an {@code AgentListener} and runs the agent anyway (see {@code ListenerAbortContractTest}), so
 * an approval gate cannot be enforced from here.
 *
 * <p>A failing {@link TrustPort} never breaks the agent invocation or the lineage.
 */
public class GovernanceAgentListener extends CasehubAgentListener {

    private static final Logger LOG = Logger.getLogger(GovernanceAgentListener.class);

    private final TrustPort trust;
    private final Supplier<String> tenancyId;

    /**
     * @param trustOrNull the trust system, or {@code null} to record lineage only
     * @param tenancyId supplies the current tenant; outcomes are not recorded without one
     */
    public GovernanceAgentListener(AuditEntryWriter writer, TrustPort trustOrNull, Supplier<String> tenancyId) {
        super(writer);
        this.trust = trustOrNull;
        this.tenancyId = Objects.requireNonNull(tenancyId, "tenancyId supplier required");
    }

    @Override
    protected Map<String, Object> startData(AgentRequest request) {
        if (trust == null) {
            return Map.of();
        }
        OptionalDouble score = trust.score(request.agentName());
        return score.isPresent() ? Map.of("trustScore", score.getAsDouble()) : Map.of();
    }

    @Override
    public void afterAgentInvocation(AgentResponse response) {
        super.afterAgentInvocation(response);
        recordOutcome(response.agentName(), response.agenticScope(), true);
    }

    @Override
    public void onAgentInvocationError(AgentInvocationError error) {
        super.onAgentInvocationError(error);
        recordOutcome(error.agentName(), error.agenticScope(), false);
    }

    private void recordOutcome(String agentName, AgenticScope scope, boolean success) {
        if (trust == null) {
            return;
        }
        try {
            String tenant = tenancyId.get();
            if (tenant == null || tenant.isBlank()) {
                LOG.debugf("Governance: no tenant — trust outcome for agent %s not recorded", agentName);
                return;
            }
            Object memoryId = scope == null ? null : scope.memoryId();
            trust.recordOutcome(agentName, subjectIdFor(memoryId), success, tenant);
        } catch (RuntimeException e) {
            LOG.warnf(e, "Governance: failed to record trust outcome for agent %s — the invocation is unaffected",
                    agentName);
        }
    }
}
