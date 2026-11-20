package io.casehub.langchain4j.governance.gate;

import java.util.Optional;

/** Where approval requests for tool calls are created and looked up. */
public interface ApprovalPort {

    /** The newest request for this call fingerprint in this tenant, if any. */
    Optional<ApprovalRequest> latest(String tenancyId, String fingerprint);

    /** Opens a new request for the call. */
    ApprovalRequest create(GatedToolCall call, String fingerprint, RiskVerdict.Gate gate);

    /** Records that an approved request has authorised its one execution. */
    void markUsed(ApprovalRequest request, String tenancyId);
}
