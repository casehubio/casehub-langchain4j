package io.casehub.langchain4j.audit;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * One auditable fact about an LLM call or an agent invocation.
 *
 * @param kind what happened — one of the constants on this type
 * @param actorId who did it, e.g. {@code llm:gpt-4o} or {@code agent:supervisor}
 * @param subjectId the aggregate the entry belongs to (one chat call, or one agent conversation)
 * @param causedByEntryId the entry that caused this one, or {@code null} for a root entry
 * @param data structured payload; never prompt or completion text
 */
public record AuditEvent(
        String kind, String actorId, UUID subjectId, UUID causedByEntryId, Map<String, Object> data) {

    public static final String AI_CHAT_COMPLETION = "AI_CHAT_COMPLETION";
    public static final String AI_CHAT_ERROR = "AI_CHAT_ERROR";
    public static final String AGENT_INVOCATION_STARTED = "AGENT_INVOCATION_STARTED";
    public static final String AGENT_INVOCATION_COMPLETED = "AGENT_INVOCATION_COMPLETED";
    public static final String AGENT_INVOCATION_FAILED = "AGENT_INVOCATION_FAILED";
    public static final String AGENT_TOOL_EXECUTED = "AGENT_TOOL_EXECUTED";
    public static final String AGENT_TOOL_GATED = "AGENT_TOOL_GATED";

    public AuditEvent {
        Objects.requireNonNull(kind, "kind required");
        Objects.requireNonNull(actorId, "actorId required");
        Objects.requireNonNull(subjectId, "subjectId required");
        data = data == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(data));
    }
}
