package io.casehub.langchain4j.audit;

import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.jboss.logging.Logger;

/**
 * Produces a tamper-evident audit entry for every LLM call.
 * <p>
 * Graceful degradation:
 * <ul>
 *   <li>LedgerEntryWriter on classpath → cryptographic ledger entries</li>
 *   <li>Ledger absent → slf4j/jboss-logging only</li>
 * </ul>
 */
public class CasehubChatModelListener implements ChatModelListener {

    private static final Logger LOG = Logger.getLogger(CasehubChatModelListener.class);
    private static final String ATTR_START_NANOS = "casehub.audit.startNanos";

    private final AuditEntryWriter writer;

    public CasehubChatModelListener(AuditEntryWriter writer) {
        this.writer = writer;
    }

    @Override
    public void onRequest(ChatModelRequestContext ctx) {
        ctx.attributes().put(ATTR_START_NANOS, System.nanoTime());
        LOG.debugf("Audit: LLM request — model=%s, messages=%d",
                ctx.chatRequest().modelName(),
                ctx.chatRequest().messages().size());
    }

    @Override
    public void onResponse(ChatModelResponseContext ctx) {
        long startNanos = (long) ctx.attributes().getOrDefault(ATTR_START_NANOS, System.nanoTime());
        long latencyMs = (System.nanoTime() - startNanos) / 1_000_000;

        ChatResponse response = ctx.chatResponse();
        // TODO: Build CaseLedgerEntry with:
        //   entryType: "AI_CHAT_COMPLETION"
        //   payload: { model, tokens (input/output/total), cost, latencyMs, finishReason }
        //   complianceSupplement: EU AI Act Art.12
        //   tenantId from CurrentPrincipal (if available)
        writer.writeCompletion(ctx.chatRequest(), response, latencyMs);

        LOG.debugf("Audit: LLM response — latency=%dms, tokens=%s",
                latencyMs,
                response.tokenUsage());
    }

    @Override
    public void onError(ChatModelErrorContext ctx) {
        // TODO: Build CaseLedgerEntry with entryType "AI_CHAT_ERROR"
        writer.writeError(ctx.chatRequest(), ctx.error());

        LOG.warnf("Audit: LLM error — %s: %s",
                ctx.error().getClass().getSimpleName(),
                ctx.error().getMessage());
    }
}
