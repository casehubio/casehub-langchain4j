package io.casehub.langchain4j.audit;

import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.jboss.logging.Logger;

/**
 * Produces one audit event for every LLM call: {@link AuditEvent#AI_CHAT_COMPLETION} on success,
 * {@link AuditEvent#AI_CHAT_ERROR} on failure.
 *
 * <p>Only metadata is recorded — model, token counts, latency, finish reason, error class and
 * message. Prompt and completion text are never recorded.
 *
 * <p>A failing {@link AuditEntryWriter} never breaks the LLM call: the failure is logged at WARN.
 */
public class CasehubChatModelListener implements ChatModelListener {

    static final String ATTR_START_NANOS = "casehub.audit.startNanos";
    static final String ATTR_SUBJECT_ID = "casehub.audit.subjectId";

    private static final Logger LOG = Logger.getLogger(CasehubChatModelListener.class);

    private final AuditEntryWriter writer;

    public CasehubChatModelListener(AuditEntryWriter writer) {
        this.writer = writer;
    }

    @Override
    public void onRequest(ChatModelRequestContext ctx) {
        ctx.attributes().put(ATTR_START_NANOS, System.nanoTime());
        ctx.attributes().put(ATTR_SUBJECT_ID, UUID.randomUUID());
    }

    @Override
    public void onResponse(ChatModelResponseContext ctx) {
        String model = modelName(ctx.chatRequest());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("model", model);
        ChatResponse response = ctx.chatResponse();
        TokenUsage usage = response == null ? null : response.tokenUsage();
        if (usage != null) {
            putIfPresent(data, "inputTokens", usage.inputTokenCount());
            putIfPresent(data, "outputTokens", usage.outputTokenCount());
            putIfPresent(data, "totalTokens", usage.totalTokenCount());
        }
        data.put("latencyMs", latencyMs(ctx.attributes()));
        if (response != null && response.finishReason() != null) {
            data.put("finishReason", response.finishReason().name());
        }
        write(new AuditEvent(
                AuditEvent.AI_CHAT_COMPLETION, actorId(model), subjectId(ctx.attributes()), null, data));
    }

    @Override
    public void onError(ChatModelErrorContext ctx) {
        String model = modelName(ctx.chatRequest());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("model", model);
        Throwable error = ctx.error();
        if (error != null) {
            data.put("errorClass", error.getClass().getName());
            putIfPresent(data, "errorMessage", error.getMessage());
        }
        write(new AuditEvent(
                AuditEvent.AI_CHAT_ERROR, actorId(model), subjectId(ctx.attributes()), null, data));
    }

    private void write(AuditEvent event) {
        try {
            writer.write(event);
        } catch (RuntimeException e) {
            LOG.warnf(e, "Audit: failed to write %s entry — the LLM call is unaffected", event.kind());
        }
    }

    private static String modelName(ChatRequest request) {
        String name = request == null ? null : request.modelName();
        return name == null || name.isBlank() ? "unknown" : name;
    }

    private static String actorId(String model) {
        return "llm:" + model;
    }

    private static UUID subjectId(Map<Object, Object> attributes) {
        return attributes.get(ATTR_SUBJECT_ID) instanceof UUID id ? id : UUID.randomUUID();
    }

    private static long latencyMs(Map<Object, Object> attributes) {
        return attributes.get(ATTR_START_NANOS) instanceof Long start
                ? Math.max(0L, (System.nanoTime() - start) / 1_000_000)
                : 0L;
    }

    private static void putIfPresent(Map<String, Object> data, String key, Object value) {
        if (value != null) {
            data.put(key, value);
        }
    }
}
