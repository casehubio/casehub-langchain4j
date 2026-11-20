package io.casehub.langchain4j.governance.gate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agentic.observability.AgentRequest;
import dev.langchain4j.agentic.scope.AgenticScope;
import dev.langchain4j.service.tool.ToolExecutor;
import io.casehub.langchain4j.audit.AuditEvent;
import io.casehub.langchain4j.audit.CasehubAgentListener;
import io.casehub.langchain4j.governance.RecordingAuditEntryWriter;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class GatedToolExecutorTest {

    private static final RiskVerdict.Gate GATE =
            new RiskVerdict.Gate("Account freeze", false, Set.of("mlro"), Duration.ofHours(24), "aml");

    private final AccountTools tools = new AccountTools();
    private final InMemoryApprovals approvals = new InMemoryApprovals();
    private final RecordingAuditEntryWriter audit = new RecordingAuditEntryWriter();
    private final AtomicReference<String> tenant = new AtomicReference<>("acme");
    private final List<GatedToolCall> classified = new CopyOnWriteArrayList<>();
    private volatile RiskPort risk = call -> {
        classified.add(call);
        return call.actionType().equals("account.freeze") ? GATE : new RiskVerdict.Autonomous();
    };
    private final CasehubAgentListener lineage = new CasehubAgentListener(audit);

    private Map<ToolSpecification, ToolExecutor> wrapped(RiskPort riskOrNull, ApprovalPort approvalsOrNull) {
        return new OversightToolGate(riskOrNull, approvalsOrNull, tenant::get, audit, lineage)
                .forAgent("treasurer").wrap(tools);
    }

    private Map<ToolSpecification, ToolExecutor> wrapped() {
        return wrapped(call -> risk.classify(call), approvals);
    }

    private static String call(Map<ToolSpecification, ToolExecutor> tools, String tool, String args) {
        ToolExecutor executor = tools.entrySet().stream()
                .filter(e -> e.getKey().name().equals(tool)).findFirst().orElseThrow().getValue();
        return executor.execute(ToolExecutionRequest.builder().id("1").name(tool).arguments(args).build(), "m1");
    }

    private static String freeze(Map<ToolSpecification, ToolExecutor> tools) {
        return call(tools, "freezeAccount", "{\"accountId\":\"A1\"}");
    }

    private List<AuditEvent> gated() {
        return audit.events.stream().filter(e -> e.kind().equals("AGENT_TOOL_GATED")).toList();
    }

    @Test
    void noRiskPort_runsTool() {
        assertThat(freeze(wrapped(null, approvals))).isEqualTo("frozen A1");
        assertThat(tools.freezes).hasValue(1);
        assertThat(approvals.calls).hasValue(0);
    }

    @Test
    void autonomous_runsTool_withoutApprovalPort() {
        assertThat(call(wrapped(), "balance", "{\"accountId\":\"A1\"}")).isEqualTo("balance of A1 is 100");
        assertThat(tools.balances).hasValue(1);
    }

    @Test
    void autonomousTool_neverTouchesApprovalPort() {
        call(wrapped(), "balance", "{\"accountId\":\"A1\"}");

        assertThat(approvals.calls).hasValue(0);
        assertThat(gated()).isEmpty();
    }

    @Test
    void actionType_fromAnnotation_elseToolName() {
        Map<ToolSpecification, ToolExecutor> tools = wrapped();
        freeze(tools);
        call(tools, "balance", "{\"accountId\":\"A1\"}");

        assertThat(classified).extracting(GatedToolCall::actionType).containsExactly("account.freeze", "balance");
        assertThat(classified.get(0).arguments()).containsExactly(Map.entry("accountId", "A1"));
        assertThat(classified.get(0).agentName()).isEqualTo("treasurer");
        assertThat(classified.get(0).tenancyId()).isEqualTo("acme");
    }

    @Test
    void gate_noRequest_createsOne_andRefusesPending() {
        String result = freeze(wrapped());

        assertThat(tools.freezes).hasValue(0);
        InMemoryApprovals.Stored request = approvals.only();
        assertThat(request.gate()).isEqualTo(GATE);
        assertThat(result).startsWith("NOT PERFORMED: ").contains("freezeAccount").contains("Account freeze")
                .contains(request.id().toString());
        assertThat(gated()).singleElement().satisfies(e -> {
            assertThat(e.data()).containsEntry("decision", "pending").containsEntry("toolName", "freezeAccount")
                    .containsEntry("workItemId", request.id().toString()).containsEntry("reason", "Account freeze");
        });
    }

    @Test
    void gate_waiting_refusesPending_withoutNewRequest() {
        Map<ToolSpecification, ToolExecutor> tools = wrapped();
        freeze(tools);
        String second = freeze(tools);

        assertThat(approvals.all()).hasSize(1);
        assertThat(second).startsWith("NOT PERFORMED: ");
        assertThat(this.tools.freezes).hasValue(0);
    }

    @Test
    void gate_rejected_refusesRejected_withoutNewRequest() {
        Map<ToolSpecification, ToolExecutor> tools = wrapped();
        freeze(tools);
        approvals.decide(approvals.only().id(), ApprovalState.REJECTED);

        String result = freeze(tools);

        assertThat(result).startsWith("NOT PERFORMED: ").contains("rejected");
        assertThat(approvals.all()).hasSize(1);
        assertThat(this.tools.freezes).hasValue(0);
        assertThat(gated()).last().satisfies(e -> assertThat(e.data()).containsEntry("decision", "rejected"));
    }

    @Test
    void gate_noDecision_createsNewRequest() {
        Map<ToolSpecification, ToolExecutor> tools = wrapped();
        freeze(tools);
        approvals.decide(approvals.only().id(), ApprovalState.NO_DECISION);

        freeze(tools);

        assertThat(approvals.all()).hasSize(2);
        assertThat(this.tools.freezes).hasValue(0);
    }

    @Test
    void gate_approved_runsToolOnce_andMarksUsed() {
        Map<ToolSpecification, ToolExecutor> tools = wrapped();
        freeze(tools);
        approvals.decide(approvals.only().id(), ApprovalState.APPROVED);

        assertThat(freeze(tools)).isEqualTo("frozen A1");

        assertThat(this.tools.freezes).hasValue(1);
        assertThat(approvals.only().used()).isTrue();
        assertThat(gated()).last().satisfies(e -> assertThat(e.data()).containsEntry("decision", "approved"));
    }

    @Test
    void approvedForOtherApproverGroups_createsNewRequest_toolNotRun() {
        Map<ToolSpecification, ToolExecutor> tools = wrapped();
        freeze(tools);
        approvals.decide(approvals.only().id(), ApprovalState.APPROVED);
        RiskVerdict.Gate stricter = new RiskVerdict.Gate("Account freeze", false, Set.of("board"), Duration.ofHours(24), "aml");
        risk = call -> stricter;

        assertThat(freeze(tools)).startsWith("NOT PERFORMED: ");
        assertThat(this.tools.freezes).hasValue(0);
        assertThat(approvals.all()).hasSize(2);
    }

    @Test
    void approvedOnce_secondIdenticalCall_createsNewRequest() {
        Map<ToolSpecification, ToolExecutor> tools = wrapped();
        freeze(tools);
        approvals.decide(approvals.only().id(), ApprovalState.APPROVED);
        freeze(tools);

        String again = freeze(tools);

        assertThat(again).startsWith("NOT PERFORMED: ");
        assertThat(this.tools.freezes).hasValue(1);
        assertThat(approvals.all()).hasSize(2);
    }

    @Test
    void markUsedFails_refusesUnchecked_toolNotRun() {
        Map<ToolSpecification, ToolExecutor> tools = wrapped();
        freeze(tools);
        approvals.decide(approvals.only().id(), ApprovalState.APPROVED);
        approvals.markUsedFailure = new IllegalStateException("work down");

        assertThat(freeze(tools)).startsWith("NOT PERFORMED: ");
        assertThat(this.tools.freezes).hasValue(0);
        assertThat(gated()).last().satisfies(e -> assertThat(e.data()).containsEntry("decision", "unchecked"));
    }

    @Test
    void gate_noApprovalPort_refusesUnchecked() {
        assertThat(freeze(wrapped(call -> risk.classify(call), null))).startsWith("NOT PERFORMED: ");
        assertThat(tools.freezes).hasValue(0);
    }

    @Test
    void unroutable_refusesUnchecked() {
        risk = call -> new RiskVerdict.Unroutable("the rule names no fixed approver group");

        assertThat(freeze(wrapped())).startsWith("NOT PERFORMED: ").contains("no fixed approver group");
        assertThat(tools.freezes).hasValue(0);
        assertThat(approvals.calls).hasValue(0);
    }

    @Test
    void classifierThrows_refusesUnchecked() {
        risk = call -> {
            throw new IllegalStateException("classifier down");
        };

        assertThat(freeze(wrapped())).startsWith("NOT PERFORMED: ");
        assertThat(tools.freezes).hasValue(0);
    }

    @Test
    void blankTenant_gatedCall_refusesUnchecked() {
        tenant.set(" ");

        assertThat(freeze(wrapped())).startsWith("NOT PERFORMED: ").contains("no tenant");
        assertThat(tools.freezes).hasValue(0);
        assertThat(approvals.calls).hasValue(0);
    }

    @Test
    void blankTenant_autonomousCall_runs() {
        tenant.set(null);

        assertThat(call(wrapped(), "balance", "{\"accountId\":\"A1\"}")).isEqualTo("balance of A1 is 100");
        assertThat(classified).singleElement().satisfies(c -> assertThat(c.tenancyId()).isNull());
    }

    private static dev.langchain4j.invocation.InvocationContext contextWithoutManagedParameters() {
        dev.langchain4j.invocation.InvocationContext context = mock(dev.langchain4j.invocation.InvocationContext.class);
        when(context.managedParameters()).thenReturn(null);
        when(context.chatMemoryId()).thenReturn("m1");
        return context;
    }

    private static String callWithContext(Map<ToolSpecification, ToolExecutor> tools, String tool) {
        ToolExecutor executor = tools.entrySet().stream()
                .filter(e -> e.getKey().name().equals(tool)).findFirst().orElseThrow().getValue();
        return executor.executeWithContext(ToolExecutionRequest.builder().id("1").name(tool)
                .arguments("{\"accountId\":\"A1\"}").build(), contextWithoutManagedParameters()).resultText();
    }

    @Test
    void executeWithContext_withoutManagedParameters_autonomousRuns() {
        assertThat(callWithContext(wrapped(), "balance")).isEqualTo("balance of A1 is 100");
    }

    @Test
    void executeWithContext_withoutManagedParameters_approvedCallRuns() {
        Map<ToolSpecification, ToolExecutor> tools = wrapped();
        assertThat(callWithContext(tools, "freezeAccount")).startsWith("NOT PERFORMED: ");
        approvals.decide(approvals.only().id(), ApprovalState.APPROVED);

        assertThat(callWithContext(tools, "freezeAccount")).isEqualTo("frozen A1");
        assertThat(this.tools.freezes).hasValue(1);
    }

    @Test
    void blankArguments_areEmptyObject() {
        call(wrapped(), "balance", "");

        assertThat(classified).singleElement().satisfies(c -> assertThat(c.arguments()).isEmpty());
    }

    @Test
    void invalidJsonArguments_refuseUnchecked_toolNotRun() {
        String result = call(wrapped(), "freezeAccount", "not json");

        assertThat(result).startsWith("NOT PERFORMED: ");
        assertThat(tools.freezes).hasValue(0);
        assertThat(classified).isEmpty();
    }

    @Test
    void otherTenant_doesNotReuseApproval_createsRequestInItsTenant() {
        Map<ToolSpecification, ToolExecutor> tools = wrapped();
        freeze(tools);
        approvals.decide(approvals.only().id(), ApprovalState.APPROVED);

        tenant.set("globex");
        String result = freeze(tools);

        assertThat(result).startsWith("NOT PERFORMED: ");
        assertThat(this.tools.freezes).hasValue(0);
        assertThat(approvals.all()).extracting(InMemoryApprovals.Stored::tenant).containsExactlyInAnyOrder("acme", "globex");
    }

    @Test
    void toolThrowsAfterApproval_approvalStaysUsed_failureReportedAsLangChain4jDoes() {
        Map<ToolSpecification, ToolExecutor> tools = wrapped();
        freeze(tools);
        approvals.decide(approvals.only().id(), ApprovalState.APPROVED);
        this.tools.freezeFailure = new IllegalStateException("bank down");

        // DefaultToolExecutor turns a tool exception into an error result; the gate must not alter it.
        assertThat(freeze(tools)).contains("bank down").doesNotStartWith("NOT PERFORMED");
        assertThat(this.tools.freezes).hasValue(1);
        assertThat(approvals.only().used()).isTrue();
    }

    @Test
    void wrappedExecutorThatThrows_exceptionPropagates() {
        ToolSpecification spec = ToolSpecification.builder().name("freezeAccount").build();
        ToolExecutor throwing = (request, memoryId) -> {
            throw new IllegalStateException("bank down");
        };
        Map<ToolSpecification, ToolExecutor> tools = new OversightToolGate(null, approvals, tenant::get, audit, lineage)
                .forAgent("treasurer").wrap(Map.of(spec, throwing));

        assertThatThrownBy(() -> freeze(tools)).hasMessage("bank down");
    }

    @Test
    void concurrentIdenticalCalls_runToolOnce() {
        Map<ToolSpecification, ToolExecutor> tools = wrapped();
        freeze(tools);
        approvals.decide(approvals.only().id(), ApprovalState.APPROVED);
        approvals.latestDelayMillis = 100;

        CompletableFuture<String> first = CompletableFuture.supplyAsync(() -> freeze(tools));
        CompletableFuture<String> second = CompletableFuture.supplyAsync(() -> freeze(tools));

        assertThat(List.of(first.join(), second.join())).filteredOn(r -> r.equals("frozen A1")).hasSize(1);
        assertThat(this.tools.freezes).hasValue(1);
    }

    @Test
    void auditEvent_chainsToCurrentInvocation_andHasNoArguments() {
        AgenticScope scope = mock(AgenticScope.class);
        when(scope.memoryId()).thenReturn("m1");
        AgentRequest request = mock(AgentRequest.class);
        when(request.agentName()).thenReturn("treasurer");
        when(request.agenticScope()).thenReturn(scope);
        lineage.beforeAgentInvocation(request);

        freeze(wrapped());

        AuditEvent gatedEvent = gated().get(0);
        assertThat(gatedEvent.causedByEntryId()).isEqualTo(audit.ids.get(0));
        assertThat(gatedEvent.actorId()).isEqualTo("agent:treasurer");
        assertThat(gatedEvent.subjectId()).isEqualTo(lineage.subjectIdFor("m1"));
        assertThat(gatedEvent.data().values()).noneMatch(v -> String.valueOf(v).contains("A1"));
    }
}
