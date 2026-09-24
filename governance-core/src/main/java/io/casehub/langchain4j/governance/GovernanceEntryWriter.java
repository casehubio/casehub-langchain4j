package io.casehub.langchain4j.governance;

/**
 * SPI for writing governance entries. Implementations delegate to
 * CaseLedgerEntry (when ledger is on classpath) or fall back to logging.
 */
public interface GovernanceEntryWriter {

    void recordInvocationStart(String agentName, Object memoryId);

    void recordInvocationComplete(String agentName, Object memoryId);

    void recordInvocationError(String agentName, Throwable error);

    void recordToolExecution(String toolName, Object memoryId);
}
