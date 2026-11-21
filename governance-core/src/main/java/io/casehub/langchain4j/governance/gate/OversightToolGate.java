package io.casehub.langchain4j.governance.gate;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.service.tool.DefaultToolExecutor;
import dev.langchain4j.service.tool.ToolExecutor;
import io.casehub.langchain4j.audit.AuditEntryWriter;
import io.casehub.langchain4j.audit.CasehubAgentListener;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Puts human approval in front of an agent's risky tool calls.
 *
 * <pre>{@code
 * AgenticServices.agentBuilder(Treasurer.class)
 *     .chatModel(model)
 *     .tools(gate.forAgent("treasurer").wrap(new PaymentTools()))
 *     .build();
 * }</pre>
 *
 * <p>Every call of a wrapped tool is classified first. A call that needs approval does not run:
 * the model receives a result starting with {@value #REFUSAL_PREFIX} that names the approval
 * request. Once a human approves it, the same call (same tool, same arguments) runs exactly once.
 * When anything needed for the decision is missing or fails, the call is refused — never run.
 */
public class OversightToolGate {

    /** Every refused call's result starts with this text. */
    public static final String REFUSAL_PREFIX = "NOT PERFORMED: ";

    private final RiskPort risk;
    private final ApprovalPort approvals;
    private final Supplier<String> tenancyId;
    private final AuditEntryWriter writer;
    private final CasehubAgentListener lineage;
    private final FingerprintLocks locks = new FingerprintLocks();

    /**
     * @param riskOrNull how calls are classified; {@code null} lets every call run
     * @param approvalsOrNull where approval requests live; {@code null} refuses every call that needs one
     * @param tenancyId supplies the current tenant; a call needing classification is refused without one
     * @param writer where gate decisions are audited
     * @param lineageOrNull the agent listener whose lineage gate entries join, or {@code null}
     */
    public OversightToolGate(RiskPort riskOrNull, ApprovalPort approvalsOrNull, Supplier<String> tenancyId,
            AuditEntryWriter writer, CasehubAgentListener lineageOrNull) {
        this.risk = riskOrNull;
        this.approvals = approvalsOrNull;
        this.tenancyId = Objects.requireNonNull(tenancyId, "tenancyId supplier required");
        this.writer = Objects.requireNonNull(writer, "writer required");
        this.lineage = lineageOrNull;
    }

    /** Starts wrapping the tools of one agent; the name identifies the agent to classifiers and approvers. */
    public ForAgent forAgent(String agentName) {
        return new ForAgent(Objects.requireNonNull(agentName, "agentName required"));
    }

    /** Wraps tools for one agent. */
    public final class ForAgent {

        private final String agentName;

        private ForAgent(String agentName) {
            this.agentName = agentName;
        }

        /** Wraps every {@code @Tool} method of the given objects, as LangChain4j would register them. */
        /**
         * Wraps every public {@code @Tool} method of the given objects, as LangChain4j would register
         * them.
         *
         * @throws IllegalArgumentException when an object has no public {@code @Tool} method (for
         *     example a framework proxy — pass the target object) or two tools share a name
         */
        public Map<ToolSpecification, ToolExecutor> wrap(Object... toolObjects) {
            Map<ToolSpecification, ToolExecutor> wrapped = new LinkedHashMap<>();
            Set<String> names = new HashSet<>();
            for (Object tools : toolObjects) {
                int found = 0;
                for (Method method : tools.getClass().getMethods()) {
                    if (method.isAnnotationPresent(Tool.class)) {
                        ToolSpecification specification = ToolSpecifications.toolSpecificationFrom(method);
                        if (!names.add(specification.name())) {
                            throw new IllegalArgumentException("two tools are named '" + specification.name() + "'");
                        }
                        GatedAction gated = method.getAnnotation(GatedAction.class);
                        String actionType = gated == null ? specification.name() : gated.value();
                        wrapped.put(specification, executor(new DefaultToolExecutor(tools, method), actionType));
                        found++;
                    }
                }
                if (found == 0) {
                    throw new IllegalArgumentException(tools.getClass().getName()
                            + " has no public methods annotated with @Tool (a proxy? pass the target object)");
                }
            }
            return wrapped;
        }

        /** Wraps existing executors; the tool name is the action type. */
        public Map<ToolSpecification, ToolExecutor> wrap(Map<ToolSpecification, ToolExecutor> tools) {
            Map<ToolSpecification, ToolExecutor> wrapped = new LinkedHashMap<>();
            tools.forEach((specification, delegate) ->
                    wrapped.put(specification, executor(delegate, specification.name())));
            return wrapped;
        }

        private ToolExecutor executor(ToolExecutor delegate, String actionType) {
            return new GatedToolExecutor(delegate, agentName, actionType, risk, approvals, tenancyId, writer,
                    lineage, locks);
        }
    }
}
