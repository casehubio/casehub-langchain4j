package io.casehub.langchain4j.governance.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.service.tool.ToolExecutor;
import io.casehub.api.spi.ActionRiskClassifier;
import io.casehub.api.spi.ClassificationContext;
import io.casehub.api.spi.RiskClassifier;
import io.casehub.api.spi.RiskDecision;
import io.casehub.api.spi.routing.StaticSetStrategy;
import io.casehub.langchain4j.audit.spring.AuditAutoConfiguration;
import io.casehub.langchain4j.governance.gate.AccountTools;
import io.casehub.langchain4j.governance.gate.FakeWorkItemApi;
import io.casehub.langchain4j.governance.gate.FakeWorkServer;
import io.casehub.langchain4j.governance.gate.WorkTokenProvider;
import io.casehub.langchain4j.governance.gate.OversightToolGate;
import io.casehub.platform.api.identity.CurrentPrincipal;
import io.casehub.work.api.WorkItemStatus;
import io.casehub.work.api.spi.WorkItemApi;
import io.casehub.worker.api.PlannedAction;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class OversightToolGateAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AuditAutoConfiguration.class, GovernanceAutoConfiguration.class));

    private static String call(OversightToolGate gate, AccountTools tools, String tool) {
        Map<ToolSpecification, ToolExecutor> wrapped = gate.forAgent("treasurer").wrap(tools);
        return wrapped.entrySet().stream().filter(e -> e.getKey().name().equals(tool)).findFirst().orElseThrow()
                .getValue().execute(ToolExecutionRequest.builder().id("1").name(tool)
                        .arguments("{\"accountId\":\"A1\"}").build(), "m1");
    }

    /** Gates account.freeze; carries the qualifier the gate looks for. */
    @RiskClassifier
    static class FreezeClassifier implements ActionRiskClassifier {
        @Override
        public RiskDecision classify(PlannedAction action, ClassificationContext context) {
            return action.actionType().equals("account.freeze")
                    ? new RiskDecision.GateRequired("Account freeze", false, StaticSetStrategy.of("mlro"),
                            Duration.ofHours(24), null, null, null)
                    : new RiskDecision.Autonomous();
        }
    }

    /** Same rule, without the qualifier (and not inheriting it): must be ignored. */
    static class UnqualifiedClassifier implements ActionRiskClassifier {
        @Override
        public RiskDecision classify(PlannedAction action, ClassificationContext context) {
            return new FreezeClassifier().classify(action, context);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class ClassifierConfig {
        @Bean
        FreezeClassifier freezeClassifier() {
            return new FreezeClassifier();
        }

        @Bean
        CurrentPrincipal currentPrincipal() {
            CurrentPrincipal principal = mock(CurrentPrincipal.class);
            when(principal.tenancyId()).thenReturn("acme");
            return principal;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class WorkConfig {
        final FakeWorkItemApi work = new FakeWorkItemApi();

        @Bean
        WorkItemApi workItemApi() {
            return work.api;
        }
    }

    @Test
    void noClassifiers_passThrough() {
        runner.run(context -> {
            AccountTools tools = new AccountTools();
            assertThat(call(context.getBean(OversightToolGate.class), tools, "freezeAccount")).isEqualTo("frozen A1");
        });
    }

    @Test
    void classifierWithoutWorkItemApi_refusesGatedCalls_runsAutonomous() {
        runner.withUserConfiguration(ClassifierConfig.class).run(context -> {
            OversightToolGate gate = context.getBean(OversightToolGate.class);
            AccountTools tools = new AccountTools();

            assertThat(call(gate, tools, "freezeAccount")).startsWith("NOT PERFORMED: ");
            assertThat(call(gate, tools, "balance")).isEqualTo("balance of A1 is 100");
            assertThat(tools.freezes).hasValue(0);
        });
    }

    @Test
    void classifierAndWorkItemApi_fullGate() {
        runner.withUserConfiguration(ClassifierConfig.class, WorkConfig.class).run(context -> {
            OversightToolGate gate = context.getBean(OversightToolGate.class);
            FakeWorkItemApi work = context.getBean(WorkConfig.class).work;
            AccountTools tools = new AccountTools();

            assertThat(call(gate, tools, "freezeAccount")).startsWith("NOT PERFORMED: ");
            work.items.keySet().forEach(id -> work.setStatus(id, WorkItemStatus.COMPLETED));
            assertThat(call(gate, tools, "freezeAccount")).isEqualTo("frozen A1");
            assertThat(tools.freezes).hasValue(1);
        });
    }

    @Test
    void unqualifiedClassifierBean_isIgnored() {
        runner.withBean(UnqualifiedClassifier.class, UnqualifiedClassifier::new).run(context -> {
            AccountTools tools = new AccountTools();
            assertThat(call(context.getBean(OversightToolGate.class), tools, "freezeAccount")).isEqualTo("frozen A1");
        });
    }

    @Test
    void userGateBean_wins() {
        OversightToolGate custom = mock(OversightToolGate.class);
        runner.withBean("customGate", OversightToolGate.class, () -> custom).run(context ->
                assertThat(context.getBean(OversightToolGate.class)).isSameAs(custom));
    }

    private static String[] remote(FakeWorkServer server, String... extra) {
        String[] base = {GovernanceAutoConfiguration.WORK_URL + "=" + server.url()};
        String[] all = java.util.Arrays.copyOf(base, base.length + extra.length);
        System.arraycopy(extra, 0, all, base.length, extra.length);
        return all;
    }

    @Test
    void remoteWork_fullGate() throws Exception {
        FakeWorkItemApi work = new FakeWorkItemApi();
        try (FakeWorkServer server = new FakeWorkServer(work, Map.of("t-acme", "acme"))) {
            runner.withUserConfiguration(ClassifierConfig.class)
                    .withPropertyValues(remote(server, GovernanceAutoConfiguration.WORK_TOKEN + "=t-acme",
                            GovernanceAutoConfiguration.WORK_TENANCY + "=acme"))
                    .run(context -> {
                        OversightToolGate gate = context.getBean(OversightToolGate.class);
                        AccountTools tools = new AccountTools();

                        assertThat(call(gate, tools, "freezeAccount")).contains("pending");
                        work.items.keySet().forEach(id -> work.setStatus(id, WorkItemStatus.COMPLETED));
                        assertThat(call(gate, tools, "freezeAccount")).isEqualTo("frozen A1");
                        assertThat(server.requests()).anySatisfy(r -> assertThat(r).startsWith("POST add-label/"));
                        assertThat(work.listedTenants).containsOnly("acme");
                    });
        }
    }

    @Test
    void workItemApiBeanWinsOverUrl() {
        runner.withUserConfiguration(ClassifierConfig.class, WorkConfig.class)
                .withPropertyValues(GovernanceAutoConfiguration.WORK_URL + "=http://127.0.0.1:1",
                        GovernanceAutoConfiguration.WORK_TOKEN + "=t", GovernanceAutoConfiguration.WORK_TENANCY + "=acme")
                .run(context -> {
                    assertThat(call(context.getBean(OversightToolGate.class), new AccountTools(), "freezeAccount"))
                            .contains("pending");
                    assertThat(context.getBean(WorkConfig.class).work.created).hasSize(1);
                });
    }

    @Test
    void urlWithoutToken_failsStartup() {
        runner.withUserConfiguration(ClassifierConfig.class)
                .withPropertyValues(GovernanceAutoConfiguration.WORK_URL + "=http://127.0.0.1:1")
                .run(context -> assertThat(context).getFailure().rootCause()
                        .hasMessageContaining(GovernanceAutoConfiguration.WORK_TOKEN)
                        .hasMessageContaining(GovernanceAutoConfiguration.WORK_TENANCY)
                        .hasMessageContaining("WorkTokenProvider"));
    }

    @Test
    void tokenProviderBeanIsUsed() throws Exception {
        FakeWorkItemApi work = new FakeWorkItemApi();
        try (FakeWorkServer server = new FakeWorkServer(work, Map.of("t-acme", "acme"))) {
            runner.withUserConfiguration(ClassifierConfig.class)
                    .withBean(WorkTokenProvider.class, () -> tenant -> Optional.of("t-" + tenant))
                    .withPropertyValues(remote(server))
                    .run(context -> {
                        assertThat(call(context.getBean(OversightToolGate.class), new AccountTools(), "freezeAccount"))
                                .contains("pending");
                        assertThat(work.created).hasSize(1);
                    });
        }
    }

    @Test
    void remoteServerError_refuses() throws Exception {
        FakeWorkItemApi work = new FakeWorkItemApi();
        try (FakeWorkServer server = new FakeWorkServer(work, Map.of("t-acme", "acme"))) {
            server.respondNext(500, "");
            runner.withUserConfiguration(ClassifierConfig.class)
                    .withPropertyValues(remote(server, GovernanceAutoConfiguration.WORK_TOKEN + "=t-acme",
                            GovernanceAutoConfiguration.WORK_TENANCY + "=acme"))
                    .run(context -> {
                        AccountTools tools = new AccountTools();
                        assertThat(call(context.getBean(OversightToolGate.class), tools, "freezeAccount"))
                                .startsWith(OversightToolGate.REFUSAL_PREFIX).contains("could not be checked");
                        assertThat(tools.freezes).hasValue(0);
                    });
        }
    }

    @Test
    void urlWithoutSchemeOrHost_failsStartup() {
        for (String url : new String[] {"localhost:8080", "ftp://work.example.com", "http:///nohost"}) {
            runner.withUserConfiguration(ClassifierConfig.class)
                    .withPropertyValues(GovernanceAutoConfiguration.WORK_URL + "=" + url,
                            GovernanceAutoConfiguration.WORK_TOKEN + "=t", GovernanceAutoConfiguration.WORK_TENANCY + "=acme")
                    .run(context -> assertThat(context).getFailure().rootCause()
                            .hasMessageContaining(GovernanceAutoConfiguration.WORK_URL));
        }
    }

    @Test
    void urlWithoutCasehubWorkApi_failsStartup() {
        runner.withUserConfiguration(ClassifierConfig.class)
                .withClassLoader(new FilteredClassLoader(WorkItemApi.class))
                .withPropertyValues(GovernanceAutoConfiguration.WORK_URL + "=http://127.0.0.1:1",
                        GovernanceAutoConfiguration.WORK_TOKEN + "=t", GovernanceAutoConfiguration.WORK_TENANCY + "=acme")
                .run(context -> assertThat(context).getFailure().rootCause()
                        .hasMessageContaining(GovernanceAutoConfiguration.WORK_URL)
                        .hasMessageContaining("casehub-work-api"));
    }

    /** Warnings {@link GovernanceAutoConfiguration} logs while {@code action} runs. */
    private static java.util.List<String> warningsDuring(Runnable action) {
        java.util.logging.Logger logger = java.util.logging.Logger.getLogger(GovernanceAutoConfiguration.class.getName());
        java.util.List<String> messages = new java.util.concurrent.CopyOnWriteArrayList<>();
        java.util.logging.Handler handler = new java.util.logging.Handler() {
            @Override
            public void publish(java.util.logging.LogRecord record) {
                if (record.getLevel().intValue() >= java.util.logging.Level.WARNING.intValue()) {
                    messages.add(record.getParameters() == null ? record.getMessage()
                            : java.text.MessageFormat.format(record.getMessage(), record.getParameters()));
                }
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        logger.addHandler(handler);
        try {
            action.run();
        } finally {
            logger.removeHandler(handler);
        }
        return messages;
    }

    @Test
    void plainHttpToRemoteHost_warns() {
        java.util.List<String> warnings = warningsDuring(() -> runner.withUserConfiguration(ClassifierConfig.class)
                .withPropertyValues(GovernanceAutoConfiguration.WORK_URL + "=http://work.example.com:8080",
                        GovernanceAutoConfiguration.WORK_TOKEN + "=t", GovernanceAutoConfiguration.WORK_TENANCY + "=acme")
                .run(context -> assertThat(context).hasNotFailed()));
        assertThat(warnings).anySatisfy(w -> assertThat(w).contains("unencrypted").contains("work.example.com"));
    }

    @Test
    void plainHttpToLoopbackOrHttps_doesNotWarn() {
        java.util.List<String> warnings = warningsDuring(() -> {
        for (String url : new String[] {"http://127.0.0.1:1", "http://localhost:1", "http://[::1]:1",
                "https://work.example.com"}) {
            runner.withUserConfiguration(ClassifierConfig.class)
                    .withPropertyValues(GovernanceAutoConfiguration.WORK_URL + "=" + url,
                            GovernanceAutoConfiguration.WORK_TOKEN + "=t", GovernanceAutoConfiguration.WORK_TENANCY + "=acme")
                    .run(context -> assertThat(context).hasNotFailed());
        }
        });
        assertThat(warnings).noneSatisfy(w -> assertThat(w).contains("unencrypted"));
    }
}
