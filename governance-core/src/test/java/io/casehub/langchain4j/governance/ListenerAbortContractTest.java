package io.casehub.langchain4j.governance;

import static org.assertj.core.api.Assertions.assertThat;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.agentic.AgenticServices;
import dev.langchain4j.agentic.observability.AgentListener;
import dev.langchain4j.agentic.observability.AgentRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Pins a LangChain4j behaviour that shapes this module's design: an exception thrown from
 * {@code beforeAgentInvocation} is caught and logged by LangChain4j ({@code ListenerNotifierUtil}),
 * and the agent runs anyway. An {@code AgentListener} can therefore observe an invocation but can
 * never veto it — which is why {@link GovernanceAgentListener} records and scores, and does not
 * enforce an oversight gate. If a LangChain4j upgrade changes this, this test fails and the
 * design can be revisited.
 */
class ListenerAbortContractTest {

    public interface Echo {
        @UserMessage("Echo {{text}}")
        @Agent("Echoes the text")
        String echo(@V("text") String text);
    }

    static class CountingModel implements ChatModel {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public ChatResponse doChat(ChatRequest request) {
            calls.incrementAndGet();
            return ChatResponse.builder().aiMessage(AiMessage.from("ok")).build();
        }
    }

    @Test
    void withoutListener_modelIsCalled() {
        CountingModel model = new CountingModel();
        Echo agent = AgenticServices.agentBuilder(Echo.class).chatModel(model).build();

        assertThat(agent.echo("hi")).isEqualTo("ok");
        assertThat(model.calls).hasValue(1);
    }

    @Test
    void exceptionInBeforeAgentInvocation_isSwallowed_agentStillRuns() {
        CountingModel model = new CountingModel();
        AgentListener vetoing = new AgentListener() {
            @Override
            public void beforeAgentInvocation(AgentRequest request) {
                throw new IllegalStateException("vetoed");
            }
        };
        Echo agent = AgenticServices.agentBuilder(Echo.class).chatModel(model).listener(vetoing).build();

        assertThat(agent.echo("hi")).isEqualTo("ok");
        assertThat(model.calls).hasValue(1);
    }
}
