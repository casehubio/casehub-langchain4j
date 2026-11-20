package io.casehub.langchain4j.governance.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.langchain4j.agentic.observability.AgentRequest;
import dev.langchain4j.agentic.observability.AgentResponse;
import dev.langchain4j.agentic.scope.AgenticScope;
import io.casehub.langchain4j.audit.CasehubAgentListener;
import io.casehub.langchain4j.audit.spring.AuditAutoConfiguration;
import io.casehub.langchain4j.governance.GovernanceAgentListener;
import io.casehub.ledger.api.model.OutcomeRecord;
import io.casehub.ledger.api.spi.OutcomeRecorder;
import io.casehub.ledger.api.spi.TrustScoreSource;
import io.casehub.platform.api.identity.CurrentPrincipal;
import java.util.OptionalDouble;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class GovernanceAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AuditAutoConfiguration.class, GovernanceAutoConfiguration.class));

    private static void invoke(CasehubAgentListener listener) {
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

    @Test
    void replacesAuditAgentListener() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(CasehubAgentListener.class);
            assertThat(context.getBean(CasehubAgentListener.class)).isInstanceOf(GovernanceAgentListener.class);
        });
    }

    @Test
    void noLedger_startsAndRecordsLineageOnly() {
        runner.run(context -> invoke(context.getBean(CasehubAgentListener.class)));
    }

    @Test
    void trustBeans_areUsedWithConfiguredConfidence() {
        runner.withUserConfiguration(TrustConfig.class)
                .withPropertyValues("casehub.langchain4j.governance.outcome-confidence=0.4")
                .run(context -> {
                    invoke(context.getBean(CasehubAgentListener.class));

                    ArgumentCaptor<OutcomeRecord> captor = ArgumentCaptor.forClass(OutcomeRecord.class);
                    verify(context.getBean(OutcomeRecorder.class)).record(captor.capture(), eq("acme"));
                    assertThat(captor.getValue().confidence()).isEqualTo(0.4);
                    assertThat(captor.getValue().actorId()).isEqualTo("agent:researcher");
                    verify(context.getBean(TrustScoreSource.class)).globalScore("agent:researcher");
                });
    }

    @Test
    void defaultConfidence_isPointSeven() {
        runner.withUserConfiguration(TrustConfig.class).run(context -> {
            invoke(context.getBean(CasehubAgentListener.class));

            ArgumentCaptor<OutcomeRecord> captor = ArgumentCaptor.forClass(OutcomeRecord.class);
            verify(context.getBean(OutcomeRecorder.class)).record(captor.capture(), any());
            assertThat(captor.getValue().confidence()).isEqualTo(0.7);
        });
    }

    @Test
    void invalidConfidence_failsStartup() {
        for (String invalid : new String[] {"0.0", "1.5"}) {
            runner.withPropertyValues("casehub.langchain4j.governance.outcome-confidence=" + invalid)
                    .run(context -> assertThat(context).hasFailed()
                            .getFailure().hasStackTraceContaining("outcome-confidence"));
        }
    }

    @Test
    void userGovernanceListener_wins() {
        GovernanceAgentListener custom = new GovernanceAgentListener(event -> null, null, () -> null);
        runner.withBean("custom", GovernanceAgentListener.class, () -> custom).run(context ->
                assertThat(context.getBean(CasehubAgentListener.class)).isSameAs(custom));
    }

    @Configuration(proxyBeanMethods = false)
    static class TrustConfig {
        @Bean
        TrustScoreSource trustScoreSource() {
            TrustScoreSource source = mock(TrustScoreSource.class);
            when(source.globalScore(anyString())).thenReturn(OptionalDouble.of(0.9));
            return source;
        }

        @Bean
        OutcomeRecorder outcomeRecorder() {
            return mock(OutcomeRecorder.class);
        }

        @Bean
        CurrentPrincipal currentPrincipal() {
            CurrentPrincipal principal = mock(CurrentPrincipal.class);
            when(principal.tenancyId()).thenReturn("acme");
            return principal;
        }
    }
}
