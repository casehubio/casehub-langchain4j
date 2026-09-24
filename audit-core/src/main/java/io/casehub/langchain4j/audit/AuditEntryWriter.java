package io.casehub.langchain4j.audit;

import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

/**
 * SPI for writing audit entries. Implementations delegate to
 * CaseLedgerEntry (when ledger is on classpath) or fall back to logging.
 */
public interface AuditEntryWriter {

    void writeCompletion(ChatRequest request, ChatResponse response, long latencyMs);

    void writeError(ChatRequest request, Throwable error);
}
