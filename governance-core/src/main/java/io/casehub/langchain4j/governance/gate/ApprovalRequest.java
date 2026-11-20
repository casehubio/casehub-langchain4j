package io.casehub.langchain4j.governance.gate;

import java.time.Instant;
import java.util.UUID;

/**
 * One approval request for one tool call.
 *
 * @param used whether an approved request already authorised an execution
 */
public record ApprovalRequest(UUID id, ApprovalState state, boolean used, Instant createdAt) {}
