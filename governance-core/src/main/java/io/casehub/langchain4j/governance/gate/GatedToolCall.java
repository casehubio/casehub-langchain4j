package io.casehub.langchain4j.governance.gate;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * One tool call an agent wants to make, as the gate sees it.
 *
 * @param agentName the agent making the call
 * @param toolName the tool's name as the model called it
 * @param actionType the {@link GatedAction} value, or the tool name
 * @param arguments the parsed arguments, by parameter name
 * @param tenancyId the tenant the call runs for
 */
public record GatedToolCall(
        String agentName, String toolName, String actionType, Map<String, Object> arguments, String tenancyId) {

    public GatedToolCall {
        Objects.requireNonNull(agentName, "agentName required");
        Objects.requireNonNull(toolName, "toolName required");
        Objects.requireNonNull(actionType, "actionType required");
        arguments = arguments == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(arguments));
    }
}
