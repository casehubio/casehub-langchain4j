package io.casehub.langchain4j.governance.gate;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * One approval request for one tool call.
 *
 * @param used whether an approved request already authorised an execution
 * @param approverGroups the groups the request was routed to; an approval only counts for a rule
 *     that names the same groups
 */
public record ApprovalRequest(UUID id, ApprovalState state, boolean used, Instant createdAt, Set<String> approverGroups) {

    public ApprovalRequest {
        approverGroups = approverGroups == null ? Set.of() : Set.copyOf(approverGroups);
    }
}
