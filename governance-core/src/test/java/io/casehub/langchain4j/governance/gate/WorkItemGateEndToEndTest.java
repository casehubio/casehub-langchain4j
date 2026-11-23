package io.casehub.langchain4j.governance.gate;

import static org.assertj.core.api.Assertions.assertThat;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.service.tool.ToolExecutor;
import io.casehub.langchain4j.governance.RecordingAuditEntryWriter;
import io.casehub.work.api.WorkItemStatus;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** The gate over the casehub-work adapter, against a store that, like the real one, ignores tenancyId. */
class WorkItemGateEndToEndTest {

    private final FakeWorkItemApi work = new FakeWorkItemApi();
    private final AtomicReference<String> tenant = new AtomicReference<>("acme");
    private final AccountTools tools = new AccountTools();
    private final Map<ToolSpecification, ToolExecutor> wrapped = new OversightToolGate(
                    call -> new RiskVerdict.Gate("Account freeze", false, Set.of("mlro"), Duration.ofHours(24), null),
                    new WorkItemApprovalPort(work.api), tenant::get, new RecordingAuditEntryWriter(), null)
            .forAgent("treasurer").wrap(tools);

    private String freeze() {
        return wrapped.entrySet().stream().filter(e -> e.getKey().name().equals("freezeAccount")).findFirst()
                .orElseThrow().getValue().execute(ToolExecutionRequest.builder().id("1").name("freezeAccount")
                        .arguments("{\"accountId\":\"A1\"}").build(), "m1");
    }

    @Test
    void approvalInTenantA_retriedInTenantB_doesNotRun() {
        assertThat(freeze()).startsWith("NOT PERFORMED: ");
        work.items.keySet().forEach(id -> work.setStatus(id, WorkItemStatus.COMPLETED));

        tenant.set("globex");
        assertThat(freeze()).startsWith("NOT PERFORMED: ");

        assertThat(tools.freezes).hasValue(0);
        assertThat(work.created).extracting(r -> r.tenancyId).containsExactly("acme", "globex");
    }

    @Test
    void approvalInTenantA_runsOnceInTenantA() {
        freeze();
        work.items.keySet().forEach(id -> work.setStatus(id, WorkItemStatus.COMPLETED));

        assertThat(freeze()).isEqualTo("frozen A1");
        assertThat(freeze()).startsWith("NOT PERFORMED: ");
        assertThat(tools.freezes).hasValue(1);
    }

    @Test
    void remoteGate_endToEnd() throws Exception {
        try (FakeWorkServer server = new FakeWorkServer(work, Map.of("t-acme", "acme"))) {
            Map<ToolSpecification, ToolExecutor> remote = remoteGate(server);

            assertThat(freeze(remote)).contains("pending");
            work.items.keySet().forEach(id -> work.setStatus(id, WorkItemStatus.COMPLETED));
            assertThat(freeze(remote)).isEqualTo("frozen A1");
            assertThat(freeze(remote)).startsWith("NOT PERFORMED: ");

            assertThat(tools.freezes).hasValue(1);
            assertThat(work.listedTenants).containsOnly("acme");
            assertThat(server.requests()).anySatisfy(r -> assertThat(r).startsWith("POST add-label/"));
        }
    }

    @Test
    void remoteGate_serverErrorRefuses() throws Exception {
        try (FakeWorkServer server = new FakeWorkServer(work, Map.of("t-acme", "acme"))) {
            server.respondNext(500, "");

            assertThat(freeze(remoteGate(server))).startsWith("NOT PERFORMED: ").contains("could not be checked");
            assertThat(tools.freezes).hasValue(0);
        }
    }

    private Map<ToolSpecification, ToolExecutor> remoteGate(FakeWorkServer server) {
        WorkItemApprovalPort port = new WorkItemApprovalPort(new HttpWorkItemGateway(server.url(),
                WorkTokenProvider.single("acme", "t-acme"), Duration.ofSeconds(5)));
        return new OversightToolGate(
                        call -> new RiskVerdict.Gate("Account freeze", false, Set.of("mlro"), Duration.ofHours(24), null),
                        port, tenant::get, new RecordingAuditEntryWriter(), null)
                .forAgent("treasurer").wrap(tools);
    }

    private static String freeze(Map<ToolSpecification, ToolExecutor> gate) {
        return gate.entrySet().stream().filter(e -> e.getKey().name().equals("freezeAccount")).findFirst()
                .orElseThrow().getValue().execute(ToolExecutionRequest.builder().id("1").name("freezeAccount")
                        .arguments("{\"accountId\":\"A1\"}").build(), "m1");
    }
}
