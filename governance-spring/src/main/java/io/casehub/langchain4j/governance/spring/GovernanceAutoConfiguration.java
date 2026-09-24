package io.casehub.langchain4j.governance.spring;

import dev.langchain4j.agentic.observability.AgentListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnClass(AgentListener.class)
public class GovernanceAutoConfiguration {
    // TODO: register GovernanceAgentListener bean with fallback writer
}
