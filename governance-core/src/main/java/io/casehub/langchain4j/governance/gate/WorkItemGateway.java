package io.casehub.langchain4j.governance.gate;

import io.casehub.work.api.WorkItemCreateRequest;
import java.util.List;
import java.util.UUID;

/**
 * The casehub-work operations {@link WorkItemApprovalPort} needs: in-process through
 * {@link WorkItemApiGateway}, or over REST through {@code HttpWorkItemGateway}.
 */
public interface WorkItemGateway {

    /** Every WorkItem of the tenant carrying the label, across all pages, in no particular order. */
    List<GateItem> findByLabel(String label, String tenancyId);

    GateItem create(WorkItemCreateRequest request, String tenancyId);

    void addLabel(UUID id, String path, String appliedBy, String tenancyId);
}
