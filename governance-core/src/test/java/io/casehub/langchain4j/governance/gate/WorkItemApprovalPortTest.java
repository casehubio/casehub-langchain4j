package io.casehub.langchain4j.governance.gate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.work.api.WorkItemCreateRequest;
import io.casehub.work.api.WorkItemStatus;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class WorkItemApprovalPortTest {

    private static final String FP = "ab".repeat(32);
    private static final String LABEL = "casehub-lc4j/gate/" + FP;

    private final FakeWorkItemApi fake = new FakeWorkItemApi();
    private final WorkItemApprovalPort port = new WorkItemApprovalPort(fake.api);
    private final GatedToolCall call =
            new GatedToolCall("treasurer", "freezeAccount", "account.freeze", Map.of("accountId", "A1"), "acme");
    private final RiskVerdict.Gate gate =
            new RiskVerdict.Gate("Account freeze", true, Set.of("mlro"), Duration.ofHours(24), "aml");

    @Test
    void create_setsTitlePayloadGroupsExpiryLabelsCallerRefTenant() throws Exception {
        ApprovalRequest request = port.create(call, FP, gate);

        WorkItemCreateRequest sent = fake.created.get(0);
        assertThat(request.state()).isEqualTo(ApprovalState.WAITING);
        assertThat(request.used()).isFalse();
        assertThat(sent.title).isEqualTo("Account freeze");
        assertThat(sent.candidateGroups).isEqualTo("mlro");
        assertThat(sent.expiresAt).isCloseTo(Instant.now().plus(Duration.ofHours(24)), within(1, ChronoUnit.MINUTES));
        assertThat(sent.scope).isEqualTo("aml");
        assertThat(sent.tenancyId).isEqualTo("acme");
        assertThat(sent.createdBy).isEqualTo("agent:treasurer");
        assertThat(sent.callerRef).isEqualTo("casehub-lc4j:gate:" + FP);
        assertThat(sent.labels).extracting(l -> l.path()).containsExactly(LABEL);
        JsonNode payload = new ObjectMapper().readTree(sent.payload);
        assertThat(payload.get("agent").asText()).isEqualTo("treasurer");
        assertThat(payload.get("tool").asText()).isEqualTo("freezeAccount");
        assertThat(payload.get("actionType").asText()).isEqualTo("account.freeze");
        assertThat(payload.get("arguments").get("accountId").asText()).isEqualTo("A1");
        assertThat(payload.get("reversible").asBoolean()).isTrue();
    }

    @Test
    void latest_picksNewestByCreatedAt() {
        fake.put(LABEL, WorkItemStatus.EXPIRED, Instant.now().minusSeconds(60));
        var newest = fake.put(LABEL, WorkItemStatus.PENDING, Instant.now());

        assertThat(port.latest("acme", FP)).hasValueSatisfying(r -> assertThat(r.id()).isEqualTo(newest.id()));
        assertThat(fake.listedTenants).containsExactly("acme");
    }

    @Test
    void latest_none_isEmpty() {
        assertThat(port.latest("acme", FP)).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(WorkItemStatus.class)
    void statusMapping(WorkItemStatus status) {
        fake.put(LABEL, status, Instant.now());

        ApprovalState expected = switch (status) {
            case COMPLETED -> ApprovalState.APPROVED;
            case REJECTED, CANCELLED, OBSOLETE -> ApprovalState.REJECTED;
            case EXPIRED, FAULTED, ESCALATED -> ApprovalState.NO_DECISION;
            default -> ApprovalState.WAITING;
        };
        assertThat(port.latest("acme", FP).orElseThrow().state()).isEqualTo(expected);
    }

    @Test
    void used_isTrueWhenUsedLabelPresent() {
        fake.put(LABEL, WorkItemStatus.COMPLETED, Instant.now(), "casehub-lc4j/gate/used");

        assertThat(port.latest("acme", FP).orElseThrow().used()).isTrue();
    }

    @Test
    void markUsed_addsUsedLabel() {
        fake.put(LABEL, WorkItemStatus.COMPLETED, Instant.now());
        ApprovalRequest approved = port.latest("acme", FP).orElseThrow();

        port.markUsed(approved, "acme");

        assertThat(port.latest("acme", FP).orElseThrow().used()).isTrue();
    }

    @Test
    void latest_readsEveryPage() {
        for (int i = 0; i < 60; i++) {
            fake.put(LABEL, WorkItemStatus.EXPIRED, Instant.now().minusSeconds(3600 + i));
        }
        var newest = fake.put(LABEL, WorkItemStatus.COMPLETED, Instant.now());

        assertThat(port.latest("acme", FP).orElseThrow().id()).isEqualTo(newest.id());
    }

    @Test
    void latest_ignoresItemWithoutMatchingCallerRef() {
        fake.putWithProvenance(LABEL, WorkItemStatus.COMPLETED, Instant.now(), "someone-else", "agent:test", "mlro");

        assertThat(port.latest("acme", FP)).isEmpty();
    }

    @Test
    void latest_ignoresItemNotCreatedByAnAgent() {
        fake.putWithProvenance(LABEL, WorkItemStatus.COMPLETED, Instant.now(), "casehub-lc4j:gate:" + FP, "mallory", "mlro");

        assertThat(port.latest("acme", FP)).isEmpty();
    }

    @Test
    void latest_reportsApproverGroups() {
        fake.putWithProvenance(LABEL, WorkItemStatus.COMPLETED, Instant.now(), "casehub-lc4j:gate:" + FP, "agent:test", "mlro,board");

        assertThat(port.latest("acme", FP).orElseThrow().approverGroups()).containsExactlyInAnyOrder("mlro", "board");
    }

    @Test
    void portOverGateway_usesOnlyGenuineNewestItem() {
        Instant t1 = Instant.now().minusSeconds(120);
        String ref = WorkItemApprovalPort.CALLER_REF_PREFIX + FP;
        GateItem approved = new GateItem(java.util.UUID.randomUUID(), WorkItemStatus.COMPLETED, "agent:treasurer", ref,
                t1, Set.of(LABEL), "mlro");
        GateItem rejected = new GateItem(java.util.UUID.randomUUID(), WorkItemStatus.REJECTED, "agent:treasurer", ref,
                t1.plusSeconds(60), Set.of(LABEL), "mlro");
        GateItem forged = new GateItem(java.util.UUID.randomUUID(), WorkItemStatus.COMPLETED, "user:x", "other",
                t1.plusSeconds(90), Set.of(LABEL), "mlro");
        WorkItemApprovalPort overGateway = new WorkItemApprovalPort(new ListGateway(java.util.List.of(approved, rejected, forged)));

        assertThat(overGateway.latest("acme", FP)).hasValueSatisfying(r -> {
            assertThat(r.id()).isEqualTo(rejected.id());
            assertThat(r.state()).isEqualTo(ApprovalState.REJECTED);
        });
    }

    @Test
    void portOverGateway_usedLabelMarksUsed() {
        GateItem item = new GateItem(java.util.UUID.randomUUID(), WorkItemStatus.COMPLETED, "agent:treasurer",
                WorkItemApprovalPort.CALLER_REF_PREFIX + FP, Instant.now(),
                Set.of(LABEL, WorkItemApprovalPort.USED_LABEL), "mlro");
        WorkItemApprovalPort overGateway = new WorkItemApprovalPort(new ListGateway(java.util.List.of(item)));

        assertThat(overGateway.latest("acme", FP)).hasValueSatisfying(r -> assertThat(r.used()).isTrue());
    }

    private record ListGateway(java.util.List<GateItem> items) implements WorkItemGateway {
        @Override
        public java.util.List<GateItem> findByLabel(String label, String tenancyId) {
            return items;
        }

        @Override
        public GateItem create(WorkItemCreateRequest request, String tenancyId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void addLabel(java.util.UUID id, String path, String appliedBy, String tenancyId) {
            throw new UnsupportedOperationException();
        }
    }
}
