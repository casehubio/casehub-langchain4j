package io.casehub.langchain4j.governance.gate;

/** Where an approval request stands. */
public enum ApprovalState {
    /** A human approved it. */
    APPROVED,
    /** Nobody has decided yet. */
    WAITING,
    /** A human rejected or cancelled it. */
    REJECTED,
    /** It ended without a decision (expired, faulted, escalated). */
    NO_DECISION
}
