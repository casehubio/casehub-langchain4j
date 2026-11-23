package io.casehub.langchain4j.governance.gate;

import io.casehub.work.api.WorkItemStatus;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * What the approval port reads from a casehub-work WorkItem.
 *
 * @param status null when the status is not known to this client — treated as still waiting
 * @param labels label paths
 * @param candidateGroups the raw comma-separated candidate groups
 */
public record GateItem(UUID id, WorkItemStatus status, String createdBy, String callerRef, Instant createdAt,
        Set<String> labels, String candidateGroups) {

    public GateItem {
        labels = labels == null ? Set.of() : Set.copyOf(labels);
    }
}
