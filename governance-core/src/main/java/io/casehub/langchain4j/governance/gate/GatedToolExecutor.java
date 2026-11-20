package io.casehub.langchain4j.governance.gate;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agentic.scope.AgenticScope;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.service.tool.ToolExecutionResult;
import dev.langchain4j.service.tool.ToolExecutor;
import io.casehub.langchain4j.audit.AuditEntryWriter;
import io.casehub.langchain4j.audit.AuditEvent;
import io.casehub.langchain4j.audit.CasehubAgentListener;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.jboss.logging.Logger;

/** Runs one tool only when the gate allows it; see {@link OversightToolGate}. */
final class GatedToolExecutor implements ToolExecutor {

    private static final Logger LOG = Logger.getLogger(GatedToolExecutor.class);

    private final ToolExecutor delegate;
    private final String agentName;
    private final String actionType;
    private final RiskPort risk;
    private final ApprovalPort approvals;
    private final Supplier<String> tenancyId;
    private final AuditEntryWriter writer;
    private final CasehubAgentListener lineage;
    private final FingerprintLocks locks;

    /** What the gate decided; {@code null} refusal means run the tool. */
    private record Decision(String refusal, String auditDecision, UUID requestId, String reason) {

        static Decision run() {
            return new Decision(null, null, null, null);
        }

        static Decision runApproved(UUID requestId, String reason) {
            return new Decision(null, "approved", requestId, reason);
        }

        static Decision refuse(String refusal, String auditDecision, UUID requestId, String reason) {
            return new Decision(refusal, auditDecision, requestId, reason);
        }
    }

    GatedToolExecutor(ToolExecutor delegate, String agentName, String actionType, RiskPort risk,
            ApprovalPort approvals, Supplier<String> tenancyId, AuditEntryWriter writer,
            CasehubAgentListener lineage, FingerprintLocks locks) {
        this.delegate = delegate;
        this.agentName = agentName;
        this.actionType = actionType;
        this.risk = risk;
        this.approvals = approvals;
        this.tenancyId = tenancyId;
        this.writer = writer;
        this.lineage = lineage;
        this.locks = locks;
    }

    @Override
    public String execute(ToolExecutionRequest request, Object memoryId) {
        Decision decision = decide(request);
        audit(request, () -> memoryId, decision);
        return decision.refusal() == null ? delegate.execute(request, memoryId) : decision.refusal();
    }

    @Override
    public ToolExecutionResult executeWithContext(ToolExecutionRequest request, InvocationContext context) {
        Decision decision = decide(request);
        audit(request, () -> memoryId(context), decision);
        if (decision.refusal() == null) {
            return delegate.executeWithContext(request, context);
        }
        return ToolExecutionResult.builder().result(decision.refusal()).resultText(decision.refusal()).build();
    }

    private Decision decide(ToolExecutionRequest request) {
        String tool = request.name();
        if (risk == null) {
            return Decision.run();
        }
        String tenant;
        Map<String, Object> arguments;
        try {
            tenant = tenancyId.get();
            if (tenant != null && tenant.isBlank()) {
                tenant = null;
            }
            arguments = CallFingerprint.parseArguments(request.arguments());
        } catch (RuntimeException e) {
            LOG.warnf(e, "Oversight gate: cannot read call to tool %s — refused", tool);
            return unchecked(tool, "its arguments could not be read", null);
        }
        GatedToolCall call = new GatedToolCall(agentName, tool, actionType, arguments, tenant);
        RiskVerdict verdict;
        try {
            verdict = risk.classify(call);
        } catch (RuntimeException e) {
            LOG.warnf(e, "Oversight gate: risk classification failed for tool %s — refused", tool);
            return unchecked(tool, "its risk could not be classified", null);
        }
        return switch (verdict) {
            case RiskVerdict.Autonomous autonomous -> Decision.run();
            case RiskVerdict.Unroutable unroutable -> unchecked(tool, unroutable.reason(), null);
            case RiskVerdict.Gate gate -> gated(call, gate);
        };
    }

    private Decision gated(GatedToolCall call, RiskVerdict.Gate gate) {
        String tool = call.toolName();
        if (call.tenancyId() == null) {
            LOG.warnf("Oversight gate: tool %s needs approval but no tenant is known (is a CurrentPrincipal bean"
                    + " available?) — refused", tool);
            return unchecked(tool, "it needs approval (" + gate.reason() + ") but no tenant is known", null);
        }
        if (approvals == null) {
            return unchecked(tool, gate.reason() + " — but no approval service is available", null);
        }
        String fingerprint;
        try {
            fingerprint = CallFingerprint.of(call.tenancyId(), tool, call.arguments());
        } catch (RuntimeException e) {
            return unchecked(tool, "its arguments could not be read", null);
        }
        return locks.withLock(fingerprint, () -> {
            try {
                Optional<ApprovalRequest> latest = approvals.latest(call.tenancyId(), fingerprint);
                if (latest.isPresent()) {
                    ApprovalRequest request = latest.get();
                    switch (request.state()) {
                        case WAITING -> {
                            return pending(tool, gate, request.id());
                        }
                        case REJECTED -> {
                            return Decision.refuse(OversightToolGate.REFUSAL_PREFIX + "'" + tool + "' was rejected by an"
                                            + " approver (" + gate.reason() + "), approval request " + request.id()
                                            + ". Do not retry it.", "rejected", request.id(), gate.reason());
                        }
                        case APPROVED -> {
                            if (!request.used()) {
                                approvals.markUsed(request, call.tenancyId());
                                return Decision.runApproved(request.id(), gate.reason());
                            }
                        }
                        case NO_DECISION -> {
                            // falls through to a new request
                        }
                    }
                }
                ApprovalRequest created = approvals.create(call, fingerprint, gate);
                return pending(tool, gate, created.id());
            } catch (RuntimeException e) {
                LOG.warnf(e, "Oversight gate: approval service failed for tool %s — refused", tool);
                return unchecked(tool, gate.reason() + " — but the approval could not be checked", null);
            }
        });
    }

    private static Decision pending(String tool, RiskVerdict.Gate gate, UUID requestId) {
        return Decision.refuse(OversightToolGate.REFUSAL_PREFIX + "'" + tool + "' requires human approval ("
                + gate.reason() + "). Approval request " + requestId + " is pending. Retry the same request after"
                + " it is approved.", "pending", requestId, gate.reason());
    }

    private static Decision unchecked(String tool, String reason, UUID requestId) {
        return Decision.refuse(OversightToolGate.REFUSAL_PREFIX + "'" + tool + "' could not be checked for approval: "
                + reason + ".", "unchecked", requestId, reason);
    }

    private void audit(ToolExecutionRequest request, Supplier<Object> memoryIdSource, Decision decision) {
        if (decision.auditDecision() == null) {
            return;
        }
        try {
            Object memoryId = memoryIdSource.get();
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("toolName", request.name());
            data.put("decision", decision.auditDecision());
            if (decision.requestId() != null) {
                data.put("workItemId", decision.requestId().toString());
            }
            if (decision.reason() != null) {
                data.put("reason", decision.reason());
            }
            UUID cause = lineage == null ? null : lineage.currentEntryId(memoryId).orElse(null);
            UUID subject = lineage != null
                    ? lineage.subjectIdFor(memoryId)
                    : UUID.nameUUIDFromBytes(("lc4j-agent:" + memoryId).getBytes(StandardCharsets.UTF_8));
            writer.write(new AuditEvent(AuditEvent.AGENT_TOOL_GATED, "agent:" + agentName, subject, cause, data));
        } catch (RuntimeException e) {
            LOG.warnf(e, "Oversight gate: failed to audit decision for tool %s", request.name());
        }
    }

    private static Object memoryId(InvocationContext context) {
        if (context == null) {
            return null;
        }
        Map<?, ?> managedParameters = context.managedParameters();
        for (Object managed : managedParameters == null ? java.util.List.of() : managedParameters.values()) {
            if (managed instanceof AgenticScope scope) {
                return scope.memoryId();
            }
        }
        return context.chatMemoryId();
    }
}
