package io.casehub.langchain4j.governance.gate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.work.api.LabelPersistence;
import io.casehub.work.api.WorkItemCreateRequest;
import io.casehub.work.api.WorkItemLabelRequest;
import io.casehub.work.api.WorkItemStatus;
import io.casehub.work.api.spi.WorkItemApi;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * {@link ApprovalPort} backed by casehub-work WorkItems. Each request carries the label
 * {@value #LABEL_PREFIX}{@code <fingerprint>}; an approved request that authorised its execution
 * also carries {@value #USED_LABEL}.
 *
 * <p>Status meaning follows engine's own action gate: {@code COMPLETED} is approved;
 * {@code REJECTED}, {@code CANCELLED}, {@code OBSOLETE} are rejected; {@code EXPIRED},
 * {@code FAULTED}, {@code ESCALATED} ended without a decision; anything else is still waiting.
 */
public class WorkItemApprovalPort implements ApprovalPort {

    public static final String LABEL_PREFIX = "casehub-lc4j/gate/";
    public static final String USED_LABEL = "casehub-lc4j/gate/used";
    public static final String CALLER_REF_PREFIX = "casehub-lc4j:gate:";
    public static final String APPLIED_BY = "system:casehub-langchain4j";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final WorkItemGateway gateway;

    /** In-process: over the casehub-work {@link WorkItemApi} bean. */
    public WorkItemApprovalPort(WorkItemApi api) {
        this(new WorkItemApiGateway(api));
    }

    /** Over any {@link WorkItemGateway} — in-process or a remote casehub-work service. */
    public WorkItemApprovalPort(WorkItemGateway gateway) {
        this.gateway = Objects.requireNonNull(gateway, "gateway required");
    }

    /**
     * Reads every matching request (the store returns label matches in no particular order) and keeps only
     * requests this gate created for this exact call: matching {@code callerRef} and an
     * {@code agent:} creator. A label alone is not trusted — labels can be added by anyone who can
     * edit a WorkItem.
     */
    @Override
    public Optional<ApprovalRequest> latest(String tenancyId, String fingerprint) {
        String callerRef = CALLER_REF_PREFIX + fingerprint;
        return gateway.findByLabel(LABEL_PREFIX + fingerprint, tenancyId).stream()
                .filter(item -> callerRef.equals(item.callerRef()) && item.createdBy() != null
                        && item.createdBy().startsWith("agent:"))
                .max(Comparator.comparing(GateItem::createdAt, Comparator.nullsFirst(Comparator.naturalOrder())))
                .map(WorkItemApprovalPort::toRequest);
    }

    @Override
    public ApprovalRequest create(GatedToolCall call, String fingerprint, RiskVerdict.Gate gate) {
        WorkItemCreateRequest request = WorkItemCreateRequest.builder()
                .title(gate.reason())
                .description("Agent '" + call.agentName() + "' wants to call tool '" + call.toolName() + "'")
                .payload(payload(call, gate))
                .candidateGroups(String.join(",", new TreeSet<>(gate.candidateGroups())))
                .expiresAt(gate.expiresIn() == null ? null : Instant.now().plus(gate.expiresIn()))
                .scope(gate.scope())
                .tenancyId(call.tenancyId())
                .createdBy("agent:" + call.agentName())
                .callerRef(CALLER_REF_PREFIX + fingerprint)
                .labels(List.of(new WorkItemLabelRequest(LABEL_PREFIX + fingerprint, LabelPersistence.MANUAL, APPLIED_BY)))
                .build();
        return toRequest(gateway.create(request, call.tenancyId()));
    }

    @Override
    public void markUsed(ApprovalRequest request, String tenancyId) {
        gateway.addLabel(request.id(), USED_LABEL, APPLIED_BY, tenancyId);
    }

    private static ApprovalRequest toRequest(GateItem item) {
        boolean used = item.labels().contains(USED_LABEL);
        Set<String> groups = item.candidateGroups() == null || item.candidateGroups().isBlank()
                ? Set.of()
                : Arrays.stream(item.candidateGroups().split(",")).map(String::trim).filter(g -> !g.isEmpty())
                        .collect(Collectors.toSet());
        return new ApprovalRequest(item.id(), state(item.status()), used, item.createdAt(), groups);
    }

    static ApprovalState state(WorkItemStatus status) {
        if (status == null) {
            return ApprovalState.WAITING;
        }
        return switch (status) {
            case COMPLETED -> ApprovalState.APPROVED;
            case REJECTED, CANCELLED, OBSOLETE -> ApprovalState.REJECTED;
            case EXPIRED, FAULTED, ESCALATED -> ApprovalState.NO_DECISION;
            default -> ApprovalState.WAITING;
        };
    }

    private static String payload(GatedToolCall call, RiskVerdict.Gate gate) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("agent", call.agentName());
        payload.put("tool", call.toolName());
        payload.put("actionType", call.actionType());
        payload.put("arguments", call.arguments());
        payload.put("reversible", gate.reversible());
        try {
            return MAPPER.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("tool arguments cannot be serialised", e);
        }
    }
}
