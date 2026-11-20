package io.casehub.langchain4j.audit.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.casehub.langchain4j.audit.AuditEntryWriter;
import io.casehub.langchain4j.audit.AuditEvent;
import io.casehub.langchain4j.audit.CasehubAgentListener;
import io.casehub.langchain4j.audit.CasehubChatModelListener;
import io.casehub.langchain4j.audit.LedgerAuditEntryWriter;
import io.casehub.langchain4j.audit.LoggingAuditEntryWriter;
import io.casehub.ledger.api.spi.LedgerAppender;
import io.casehub.platform.api.identity.CurrentPrincipal;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class AuditAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AuditAutoConfiguration.class));

    @Test
    void noLedger_usesLoggingWriter() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(AuditEntryWriter.class)).isInstanceOf(LoggingAuditEntryWriter.class);
            assertThat(context).hasSingleBean(CasehubChatModelListener.class);
            assertThat(context).hasSingleBean(CasehubAgentListener.class);
        });
    }

    @Test
    void ledgerAndPrincipalBeans_usesLedgerWriter() {
        runner.withUserConfiguration(LedgerConfig.class).run(context -> {
            AuditEntryWriter writer = context.getBean(AuditEntryWriter.class);
            assertThat(writer).isInstanceOf(LedgerAuditEntryWriter.class);

            writer.write(new AuditEvent(AuditEvent.AI_CHAT_COMPLETION, "llm:x", UUID.randomUUID(), null, Map.of()));

            verify(context.getBean(LedgerAppender.class)).append(any(), eq("acme"));
        });
    }

    @Test
    void ledgerWithoutPrincipal_usesLoggingWriter() {
        runner.withBean(LedgerAppender.class, () -> mock(LedgerAppender.class)).run(context ->
                assertThat(context.getBean(AuditEntryWriter.class)).isInstanceOf(LoggingAuditEntryWriter.class));
    }

    @Test
    void userWriterBean_wins() {
        AuditEntryWriter custom = event -> UUID.randomUUID();
        runner.withBean("customWriter", AuditEntryWriter.class, () -> custom).run(context ->
                assertThat(context.getBean(AuditEntryWriter.class)).isSameAs(custom));
    }

    @Test
    void userAgentListenerSubclass_replacesDefault() {
        runner.withUserConfiguration(CustomListenerConfig.class).run(context -> {
            assertThat(context).hasSingleBean(CasehubAgentListener.class);
            assertThat(context.getBean(CasehubAgentListener.class)).isInstanceOf(CustomAgentListener.class);
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class LedgerConfig {
        @Bean
        LedgerAppender ledgerAppender() {
            LedgerAppender appender = mock(LedgerAppender.class);
            when(appender.append(any(), any())).thenReturn(UUID.randomUUID());
            return appender;
        }

        @Bean
        CurrentPrincipal currentPrincipal() {
            CurrentPrincipal principal = mock(CurrentPrincipal.class);
            when(principal.tenancyId()).thenReturn("acme");
            return principal;
        }
    }

    static class CustomAgentListener extends CasehubAgentListener {
        CustomAgentListener(AuditEntryWriter writer) {
            super(writer);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomListenerConfig {
        @Bean
        CustomAgentListener customAgentListener(AuditEntryWriter writer) {
            return new CustomAgentListener(writer);
        }
    }
}
