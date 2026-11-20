package io.casehub.langchain4j.governance;

import java.util.OptionalDouble;
import java.util.UUID;

/**
 * What {@link GovernanceAgentListener} needs from a trust system: read an agent's current score,
 * and report how an invocation ended. {@link LedgerTrustPort} adapts the CaseHub ledger to it.
 */
public interface TrustPort {

    /** The agent's current trust score, or empty when it has none yet. */
    OptionalDouble score(String agentName);

    /**
     * Reports the outcome of one invocation.
     *
     * @param subjectId the ledger subject of the conversation the invocation belongs to
     * @param success {@code true} when the invocation completed, {@code false} when it failed
     */
    void recordOutcome(String agentName, UUID subjectId, boolean success, String tenancyId);
}
