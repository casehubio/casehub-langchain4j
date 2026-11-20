package io.casehub.langchain4j.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.langchain4j.agentic.observability.AfterAgentToolExecution;
import dev.langchain4j.agentic.observability.AgentInvocationError;
import dev.langchain4j.agentic.observability.AgentRequest;
import dev.langchain4j.agentic.observability.AgentResponse;
import dev.langchain4j.agentic.scope.AgenticScope;
import org.junit.jupiter.api.Test;

class CasehubAgentListenerTest {

    private final RecordingAuditEntryWriter writer = new RecordingAuditEntryWriter();
    private final CasehubAgentListener listener = new CasehubAgentListener(writer);
    private final AgenticScope scope = scope("m1");

    static AgenticScope scope(Object memoryId) {
        AgenticScope scope = mock(AgenticScope.class);
        when(scope.memoryId()).thenReturn(memoryId);
        return scope;
    }

    static AgentRequest request(String agent, AgenticScope scope) {
        AgentRequest request = mock(AgentRequest.class);
        when(request.agentName()).thenReturn(agent);
        when(request.agenticScope()).thenReturn(scope);
        return request;
    }

    static AgentResponse response(String agent, AgenticScope scope) {
        AgentResponse response = mock(AgentResponse.class);
        when(response.agentName()).thenReturn(agent);
        when(response.agenticScope()).thenReturn(scope);
        return response;
    }

    static AgentInvocationError error(String agent, AgenticScope scope, Throwable cause) {
        AgentInvocationError error = mock(AgentInvocationError.class);
        when(error.agentName()).thenReturn(agent);
        when(error.agenticScope()).thenReturn(scope);
        when(error.error()).thenReturn(cause);
        return error;
    }

    static AfterAgentToolExecution tool(String agent, String toolName, AgenticScope scope) {
        AfterAgentToolExecution after = mock(AfterAgentToolExecution.class, RETURNS_DEEP_STUBS);
        when(after.agenticScope()).thenReturn(scope);
        when(after.agentInstance().name()).thenReturn(agent);
        when(after.toolExecution().request().name()).thenReturn(toolName);
        return after;
    }

    @Test
    void rootInvocation_startedHasNoCause_completedCausedByStarted() {
        listener.beforeAgentInvocation(request("supervisor", scope));
        listener.afterAgentInvocation(response("supervisor", scope));

        assertThat(writer.events).extracting(AuditEvent::kind)
                .containsExactly("AGENT_INVOCATION_STARTED", "AGENT_INVOCATION_COMPLETED");
        AuditEvent started = writer.events.get(0);
        AuditEvent completed = writer.events.get(1);
        assertThat(started.causedByEntryId()).isNull();
        assertThat(started.actorId()).isEqualTo("agent:supervisor");
        assertThat(started.data()).containsEntry("agentName", "supervisor");
        assertThat(completed.causedByEntryId()).isEqualTo(writer.ids.get(0));
        assertThat(completed.actorId()).isEqualTo("agent:supervisor");
        assertThat(completed.data()).containsOnlyKeys("agentName", "durationMs");
        assertThat((Long) completed.data().get("durationMs")).isGreaterThanOrEqualTo(0L);
    }

    @Test
    void nestedInvocation_childStartedCausedByParentStarted() {
        listener.beforeAgentInvocation(request("parent", scope));
        listener.beforeAgentInvocation(request("child", scope));

        assertThat(writer.events.get(1).causedByEntryId()).isEqualTo(writer.ids.get(0));
    }

    @Test
    void nestedInvocation_parentCompletedCausedByParentStarted() {
        listener.beforeAgentInvocation(request("parent", scope));
        listener.beforeAgentInvocation(request("child", scope));
        listener.afterAgentInvocation(response("child", scope));
        listener.afterAgentInvocation(response("parent", scope));

        assertThat(writer.events.get(2).causedByEntryId()).isEqualTo(writer.ids.get(1));
        assertThat(writer.events.get(3).causedByEntryId()).isEqualTo(writer.ids.get(0));
        assertThat(writer.events.get(3).actorId()).isEqualTo("agent:parent");
    }

    @Test
    void toolExecution_causedByCurrentInvocation() {
        listener.beforeAgentInvocation(request("researcher", scope));
        listener.afterAgentToolExecution(tool("researcher", "search", scope));

        AuditEvent toolEvent = writer.events.get(1);
        assertThat(toolEvent.kind()).isEqualTo("AGENT_TOOL_EXECUTED");
        assertThat(toolEvent.causedByEntryId()).isEqualTo(writer.ids.get(0));
        assertThat(toolEvent.actorId()).isEqualTo("agent:researcher");
        assertThat(toolEvent.data()).containsEntry("toolName", "search");
    }

    @Test
    void failedChild_popsFrame_nextChildChainsToParent() {
        listener.beforeAgentInvocation(request("parent", scope));
        listener.beforeAgentInvocation(request("child1", scope));
        listener.onAgentInvocationError(error("child1", scope, new IllegalStateException("boom")));
        listener.beforeAgentInvocation(request("child2", scope));

        AuditEvent failed = writer.events.get(2);
        assertThat(failed.kind()).isEqualTo("AGENT_INVOCATION_FAILED");
        assertThat(failed.causedByEntryId()).isEqualTo(writer.ids.get(1));
        assertThat(failed.data()).containsOnlyKeys("agentName", "errorClass");
        assertThat(failed.data()).containsEntry("errorClass", "java.lang.IllegalStateException");
        assertThat(writer.events.get(3).causedByEntryId()).isEqualTo(writer.ids.get(0));
    }

    @Test
    void sameMemoryId_sameSubjectId() {
        listener.beforeAgentInvocation(request("a", scope("m1")));
        listener.beforeAgentInvocation(request("b", scope("m1")));

        assertThat(writer.events.get(0).subjectId()).isEqualTo(writer.events.get(1).subjectId());
    }

    @Test
    void differentMemoryId_differentSubjectId() {
        listener.beforeAgentInvocation(request("a", scope("m1")));
        listener.beforeAgentInvocation(request("a", scope("m2")));

        assertThat(writer.events.get(0).subjectId()).isNotEqualTo(writer.events.get(1).subjectId());
        assertThat(writer.events.get(1).causedByEntryId()).isNull();
    }

    @Test
    void scopeDestroyed_clearsState() {
        listener.beforeAgentInvocation(request("abandoned", scope));
        listener.beforeAgenticScopeDestroyed(scope);
        listener.beforeAgentInvocation(request("fresh", scope));

        assertThat(writer.events.get(1).causedByEntryId()).isNull();
    }

    @Test
    void writerThrows_doesNotPropagate() {
        writer.failure = new RuntimeException("ledger down");

        assertThatCode(() -> {
            listener.beforeAgentInvocation(request("a", scope));
            listener.afterAgentToolExecution(tool("a", "search", scope));
            listener.afterAgentInvocation(response("a", scope));
            listener.onAgentInvocationError(error("a", scope, new RuntimeException("x")));
        }).doesNotThrowAnyException();
    }

    @Test
    void inheritedBySubagents_isTrue() {
        assertThat(listener.inheritedBySubagents()).isTrue();
    }

    @Test
    void suspendedAgent_clearsItsFrames_soTheNextInvocationIsARoot() {
        listener.beforeAgentInvocation(request("waits-for-human", scope));
        listener.onAgenticSystemSuspended(scope);
        listener.beforeAgentInvocation(request("resumed", scope));

        assertThat(writer.events.get(1).causedByEntryId()).isNull();
    }

    @Test
    void toolCallOutsideAnInvocation_doesNotStartTracking() {
        listener.afterAgentToolExecution(tool("a", "search", scope));

        assertThat(listener.trackedConversations()).isZero();
    }

    @Test
    void currentEntryId_isTheOpenInvocation() {
        listener.beforeAgentInvocation(request("parent", scope));
        listener.beforeAgentInvocation(request("child", scope));
        assertThat(listener.currentEntryId("m1")).contains(writer.ids.get(1));

        listener.afterAgentInvocation(response("child", scope));
        assertThat(listener.currentEntryId("m1")).contains(writer.ids.get(0));

        listener.afterAgentInvocation(response("parent", scope));
        assertThat(listener.currentEntryId("m1")).isEmpty();
    }

    @Test
    void currentEntryId_unknownMemoryId_isEmpty() {
        assertThat(listener.currentEntryId("nobody")).isEmpty();
    }

    @Test
    void toolNotPerformed_marksEntry() {
        CasehubAgentListener refusing = new CasehubAgentListener(writer) {
            @Override
            protected boolean toolPerformed(dev.langchain4j.agentic.observability.AfterAgentToolExecution after) {
                return false;
            }
        };
        refusing.beforeAgentInvocation(request("a", scope));
        refusing.afterAgentToolExecution(tool("a", "freezeAccount", scope));

        assertThat(writer.events.get(1).data()).containsEntry("performed", false);
    }

    @Test
    void toolPerformed_byDefault_noPerformedKey() {
        listener.beforeAgentInvocation(request("a", scope));
        listener.afterAgentToolExecution(tool("a", "search", scope));

        assertThat(writer.events.get(1).data()).doesNotContainKey("performed");
    }
}
