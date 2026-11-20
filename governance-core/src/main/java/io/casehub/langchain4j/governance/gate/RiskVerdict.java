package io.casehub.langchain4j.governance.gate;

import java.time.Duration;
import java.util.Set;

/** The outcome of classifying one tool call. */
public sealed interface RiskVerdict {

    /** The call may run without approval. */
    record Autonomous() implements RiskVerdict {}

    /**
     * The call needs a human's approval.
     *
     * @param reason shown to the approver and in the refusal
     * @param reversible presentational: whether the action can be undone
     * @param candidateGroups who may approve; never empty
     * @param expiresIn how long the request stays open, or {@code null} for no expiry
     * @param scope the classifier's scope key, or {@code null}
     */
    record Gate(String reason, boolean reversible, Set<String> candidateGroups, Duration expiresIn, String scope)
            implements RiskVerdict {

        public Gate {
            candidateGroups = Set.copyOf(candidateGroups);
        }
    }

    /** Approval is required but cannot be routed to anyone safely; the call must be refused. */
    record Unroutable(String reason) implements RiskVerdict {}
}
