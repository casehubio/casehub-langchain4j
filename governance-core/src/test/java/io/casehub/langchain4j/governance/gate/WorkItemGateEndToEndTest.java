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
}
