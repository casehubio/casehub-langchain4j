package io.casehub.langchain4j.audit;

import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.jboss.logging.Logger;

/**
 * Fallback writer that logs audit entries when no ledger is available.
 */
public class LoggingAuditEntryWriter implements AuditEntryWriter {

    private static final Logger LOG = Logger.getLogger(LoggingAuditEntryWriter.class);

    @Override
    public void writeCompletion(ChatRequest request, ChatResponse response, long latencyMs) {
        LOG.infof("AUDIT [completion] model=%s latency=%dms tokens=%s",
                request.model(),
                latencyMs,
                response.metadata().tokenUsage());
    }

    @Override
    public void writeError(ChatRequest request, Throwable error) {
        LOG.warnf("AUDIT [error] model=%s error=%s: %s",
                request.model(),
                error.getClass().getSimpleName(),
                error.getMessage());
    }
}
