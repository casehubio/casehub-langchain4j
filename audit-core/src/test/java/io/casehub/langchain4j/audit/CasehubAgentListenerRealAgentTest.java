package io.casehub.langchain4j.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.agentic.AgenticServices;
import dev.langchain4j.agentic.UntypedAgent;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Drives the listener through real LangChain4j agents instead of hand-ordered mocks. */
class CasehubAgentListenerRealAgentTest {

    public interface Echo {
        @UserMessage("Echo {{text}}")
        @Agent("Echoes the text")
        String echo(@V("text") String text);
    }

    private final RecordingAuditEntryWriter writer = new RecordingAuditEntryWriter();
    private final CasehubAgentListener listener = new CasehubAgentListener(writer);

    private static ChatModel answering(String answer) {
        return new ChatModel() {
            @Override
            public ChatResponse doChat(ChatRequest request) {
                return ChatResponse.builder().aiMessage(AiMessage.from(answer)).build();
            }
        };
    }

    @Test
    void standaloneAgents_leaveNoTrackedState() {
        Echo agent = AgenticServices.agentBuilder(Echo.class).chatModel(answering("ok")).listener(listener).build();

        for (int i = 0; i < 50; i++) {
            agent.echo("hi " + i);
        }

        assertThat(listener.trackedConversations()).isZero();
        assertThat(writer.events).hasSize(100);
        assertThat(writer.events.get(1).causedByEntryId()).isEqualTo(writer.ids.get(0));
    }

    @Test
    void failingStandaloneAgent_leavesNoTrackedState() {
        ChatModel failing = new ChatModel() {
            @Override
            public ChatResponse doChat(ChatRequest request) {
                throw new IllegalStateException("provider down");
            }
        };
        Echo agent = AgenticServices.agentBuilder(Echo.class).chatModel(failing).listener(listener).build();

        assertThatThrownBy(() -> agent.echo("hi")).hasStackTraceContaining("provider down");

        assertThat(listener.trackedConversations()).isZero();
        assertThat(writer.events).extracting(AuditEvent::kind).last().isEqualTo("AGENT_INVOCATION_FAILED");
    }

    @Test
    void sequence_subAgentsChainToTheWorkflowInvocation() {
        Echo first = AgenticServices.agentBuilder(Echo.class).chatModel(answering("one")).outputKey("text").build();
        Echo second = AgenticServices.agentBuilder(Echo.class).chatModel(answering("two")).outputKey("text").build();
        UntypedAgent workflow = AgenticServices.sequenceBuilder()
                .subAgents(first, second)
                .outputKey("text")
                .listener(listener)
                .build();

        workflow.invoke(Map.of("text", "hi"));

        List<AuditEvent> started = writer.events.stream()
                .filter(e -> e.kind().equals(AuditEvent.AGENT_INVOCATION_STARTED))
                .toList();
        assertThat(started).hasSize(3);
        java.util.UUID rootId = writer.ids.get(writer.events.indexOf(started.get(0)));
        assertThat(started.get(0).causedByEntryId()).isNull();
        assertThat(started.get(1).causedByEntryId()).isEqualTo(rootId);
        assertThat(started.get(2).causedByEntryId()).isEqualTo(rootId);
        assertThat(listener.trackedConversations()).isZero();
    }
}
