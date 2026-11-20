package io.casehub.langchain4j.governance.quarkus;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.langchain4j.agentic.observability.AgentRequest;
import dev.langchain4j.agentic.observability.AgentResponse;
import dev.langchain4j.agentic.scope.AgenticScope;
import io.casehub.langchain4j.audit.CasehubAgentListener;

final class Invocations {

    private Invocations() {}

    /** Drives one successful invocation of agent "researcher" through the listener. */
    static void invoke(CasehubAgentListener listener) {
        AgenticScope scope = mock(AgenticScope.class);
        when(scope.memoryId()).thenReturn("m1");
        AgentRequest request = mock(AgentRequest.class);
        when(request.agentName()).thenReturn("researcher");
        when(request.agenticScope()).thenReturn(scope);
        AgentResponse response = mock(AgentResponse.class);
        when(response.agentName()).thenReturn("researcher");
        when(response.agenticScope()).thenReturn(scope);
        listener.beforeAgentInvocation(request);
        listener.afterAgentInvocation(response);
    }
}
