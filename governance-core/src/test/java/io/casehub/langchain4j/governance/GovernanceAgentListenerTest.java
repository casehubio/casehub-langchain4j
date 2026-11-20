package io.casehub.langchain4j.governance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.langchain4j.agentic.observability.AgentInvocationError;
import dev.langchain4j.agentic.observability.AgentRequest;
import dev.langchain4j.agentic.observability.AgentResponse;
import dev.langchain4j.agentic.scope.AgenticScope;
import io.casehub.langchain4j.audit.AuditEvent;
import java.nio.charset.StandardCharsets;
import java.util.OptionalDouble;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class GovernanceAgentListenerTest {

    private static final UUID SUBJECT = UUID.nameUUIDFromBytes("lc4j-agent:m1".getBytes(StandardCharsets.UTF_8));

    private final RecordingAuditEntryWriter writer = new RecordingAuditEntryWriter();
    private final TrustPort trust = mock(TrustPort.class);
    private final AtomicReference<String> tenancy = new AtomicReference<>("acme");
    private final GovernanceAgentListener listener = new GovernanceAgentListener(writer, trust, tenancy::get);
    private final AgenticScope scope = mock(AgenticScope.class);

    GovernanceAgentListenerTest() {
        when(scope.memoryId()).thenReturn("m1");
        when(trust.score(anyString())).thenReturn(OptionalDouble.empty());
    }

    private AgentRequest request(String agent) {
        AgentRequest request = mock(AgentRequest.class);
        when(request.agentName()).thenReturn(agent);
        when(request.agenticScope()).thenReturn(scope);
        return request;
    }

    private AgentResponse response(String agent) {
        AgentResponse response = mock(AgentResponse.class);
        when(response.agentName()).thenReturn(agent);
        when(response.agenticScope()).thenReturn(scope);
        return response;
    }

    private AgentInvocationError error(String agent, Throwable cause) {
        AgentInvocationError error = mock(AgentInvocationError.class);
        when(error.agentName()).thenReturn(agent);
        when(error.agenticScope()).thenReturn(scope);
        when(error.error()).thenReturn(cause);
        return error;
    }

    @Test
    void noTrustPort_behavesLikeAuditListener() {
        GovernanceAgentListener plain = new GovernanceAgentListener(writer, null, tenancy::get);

        plain.beforeAgentInvocation(request("supervisor"));
        plain.afterAgentInvocation(response("supervisor"));

        assertThat(writer.events).extracting(AuditEvent::kind)
                .containsExactly("AGENT_INVOCATION_STARTED", "AGENT_INVOCATION_COMPLETED");
        assertThat(writer.events.get(0).data()).containsOnlyKeys("agentName");
        assertThat(writer.events.get(1).causedByEntryId()).isEqualTo(writer.ids.get(0));
        assertThat(plain.inheritedBySubagents()).isTrue();
    }

    @Test
    void trustScorePresent_recordedInStartedData() {
        when(trust.score("researcher")).thenReturn(OptionalDouble.of(0.82));

        listener.beforeAgentInvocation(request("researcher"));

        assertThat(writer.events.get(0).data()).containsEntry("trustScore", 0.82);
    }

    @Test
    void trustScoreAbsent_keyOmitted() {
        listener.beforeAgentInvocation(request("researcher"));

        assertThat(writer.events.get(0).data()).doesNotContainKey("trustScore");
    }

    @Test
    void success_recordsPositiveOutcome() {
        listener.beforeAgentInvocation(request("researcher"));
        listener.afterAgentInvocation(response("researcher"));

        verify(trust).recordOutcome("researcher", SUBJECT, true, "acme");
        assertThat(writer.events.get(0).subjectId()).isEqualTo(SUBJECT);
    }

    @Test
    void error_recordsNegativeOutcome() {
        listener.beforeAgentInvocation(request("researcher"));
        listener.onAgentInvocationError(error("researcher", new IllegalStateException("boom")));

        verify(trust).recordOutcome("researcher", SUBJECT, false, "acme");
        assertThat(writer.events.get(1).kind()).isEqualTo("AGENT_INVOCATION_FAILED");
    }

    @Test
    void trustPortThrows_doesNotPropagate_andLineageIsKept() {
        when(trust.score(anyString())).thenThrow(new RuntimeException("trust store down"));
        doThrow(new RuntimeException("trust store down"))
                .when(trust).recordOutcome(anyString(), any(), anyBoolean(), anyString());

        assertThatCode(() -> {
            listener.beforeAgentInvocation(request("researcher"));
            listener.afterAgentInvocation(response("researcher"));
            listener.onAgentInvocationError(error("researcher", new RuntimeException("x")));
        }).doesNotThrowAnyException();
        assertThat(writer.events).extracting(AuditEvent::kind).containsExactly(
                "AGENT_INVOCATION_STARTED", "AGENT_INVOCATION_COMPLETED", "AGENT_INVOCATION_FAILED");
    }

    @Test
    void blankTenancy_skipsOutcomeRecording_keepsLineage() {
        tenancy.set(" ");

        listener.beforeAgentInvocation(request("researcher"));
        listener.afterAgentInvocation(response("researcher"));
        tenancy.set(null);
        listener.onAgentInvocationError(error("researcher", new RuntimeException("x")));

        verify(trust, never()).recordOutcome(anyString(), any(), anyBoolean(), any());
        assertThat(writer.events).hasSize(3);
    }
}
