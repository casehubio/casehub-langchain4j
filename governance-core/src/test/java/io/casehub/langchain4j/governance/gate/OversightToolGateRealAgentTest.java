package io.casehub.langchain4j.governance.gate;

import static org.assertj.core.api.Assertions.assertThat;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agentic.Agent;
import dev.langchain4j.agentic.AgenticServices;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import io.casehub.langchain4j.audit.AuditEvent;
import io.casehub.langchain4j.governance.GovernanceAgentListener;
import io.casehub.langchain4j.governance.RecordingAuditEntryWriter;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/** Runs the gate inside a real LangChain4j agent: the model asks for a gated tool, the gate refuses. */
class OversightToolGateRealAgentTest {

    public interface Treasurer {
        @UserMessage("Handle: {{task}}")
        @Agent("Handles treasury tasks")
        String handle(@V("task") String task);
    }

    /** First answer: call freezeAccount. Second answer: plain text. Records what it was sent. */
    static class ScriptedModel implements ChatModel {
        final List<List<ChatMessage>> requests = new CopyOnWriteArrayList<>();

        @Override
        public ChatResponse doChat(ChatRequest request) {
            requests.add(request.messages());
            if (requests.size() == 1) {
                return ChatResponse.builder().aiMessage(AiMessage.from(ToolExecutionRequest.builder()
                        .id("call-1").name("freezeAccount").arguments("{\"accountId\":\"A1\"}").build())).build();
            }
            return ChatResponse.builder().aiMessage(AiMessage.from("I asked for approval.")).build();
        }
    }

    @Test
    void gatedToolCall_isRefused_modelSeesRefusal_auditMarksNotPerformed() {
        RecordingAuditEntryWriter audit = new RecordingAuditEntryWriter();
        GovernanceAgentListener listener = new GovernanceAgentListener(audit, null, () -> "acme");
        InMemoryApprovals approvals = new InMemoryApprovals();
        RiskPort risk = call -> new RiskVerdict.Gate("Account freeze", false, Set.of("mlro"), Duration.ofHours(24), null);
        OversightToolGate gate = new OversightToolGate(risk, approvals, () -> "acme", audit, listener);
        AccountTools tools = new AccountTools();
        ScriptedModel model = new ScriptedModel();

        Treasurer agent = AgenticServices.agentBuilder(Treasurer.class)
                .chatModel(model)
                .tools(gate.forAgent("treasurer").wrap(tools))
                .listener(listener)
                .build();

        assertThat(agent.handle("freeze A1")).isEqualTo("I asked for approval.");

        assertThat(tools.freezes).hasValue(0);
        assertThat(approvals.all()).hasSize(1);
        ToolExecutionResultMessage toolResult = model.requests.get(1).stream()
                .filter(ToolExecutionResultMessage.class::isInstance).map(ToolExecutionResultMessage.class::cast)
                .findFirst().orElseThrow();
        assertThat(toolResult.text()).startsWith("NOT PERFORMED: ");
        assertThat(audit.events).filteredOn(e -> e.kind().equals(AuditEvent.AGENT_TOOL_GATED)).singleElement()
                .satisfies(e -> assertThat(e.causedByEntryId()).isNotNull());
        assertThat(audit.events).filteredOn(e -> e.kind().equals(AuditEvent.AGENT_TOOL_EXECUTED)).singleElement()
                .satisfies(e -> assertThat(e.data()).containsEntry("performed", false));
    }
}
