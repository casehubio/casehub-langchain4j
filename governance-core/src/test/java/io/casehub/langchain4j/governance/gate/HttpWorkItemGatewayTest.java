package io.casehub.langchain4j.governance.gate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.casehub.work.api.LabelPersistence;
import io.casehub.work.api.WorkItemCreateRequest;
import io.casehub.work.api.WorkItemLabelRequest;
import io.casehub.work.api.WorkItemStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class HttpWorkItemGatewayTest {

    private static final String FP = "cd".repeat(32);
    private static final String LABEL = WorkItemApprovalPort.LABEL_PREFIX + FP;

    private final FakeWorkItemApi work = new FakeWorkItemApi();
    private final FakeWorkServer server;
    private final HttpWorkItemGateway gateway;

    HttpWorkItemGatewayTest() throws Exception {
        server = new FakeWorkServer(work, Map.of("tA", "A", "tB", "B"));
        gateway = new HttpWorkItemGateway(server.url(), WorkTokenProvider.single("A", "tA"), Duration.ofSeconds(5));
    }

    @AfterEach
    void stop() {
        server.close();
    }

    @Test
    void findByLabel_followsHasMoreAcrossPages() {
        for (int i = 0; i < 60; i++) {
            work.put(LABEL, WorkItemStatus.PENDING, Instant.now().minusSeconds(i));
        }

        assertThat(gateway.findByLabel(LABEL, "A")).hasSize(60);
        assertThat(server.requests()).hasSize(2);
        assertThat(server.requests().get(0)).contains("list-all").contains("offset=0").contains("limit=50")
                .contains("label=" + LABEL);
        assertThat(server.requests().get(1)).contains("offset=50");
    }

    @Test
    void findByLabel_stopsOnEmptyPageEvenIfHasMore() {
        UUID id = UUID.randomUUID();
        server.respondNext(200, "{\"items\":[{\"id\":\"" + id + "\",\"status\":\"PENDING\"}],\"totalCount\":9,\"hasMore\":true}");
        server.respondNext(200, "{\"items\":[],\"totalCount\":9,\"hasMore\":true}");

        assertThat(gateway.findByLabel(LABEL, "A")).extracting(GateItem::id).containsExactly(id);
        assertThat(server.requests()).hasSize(2);
    }

    @Test
    void findByLabel_mapsUsedLabelAndUnknownStatus() {
        var item = work.put(LABEL, WorkItemStatus.COMPLETED, Instant.parse("2026-11-01T10:15:30Z"),
                WorkItemApprovalPort.USED_LABEL);
        GateItem mapped = gateway.findByLabel(LABEL, "A").get(0);
        assertThat(mapped.id()).isEqualTo(item.id());
        assertThat(mapped.status()).isEqualTo(WorkItemStatus.COMPLETED);
        assertThat(mapped.createdAt()).isEqualTo(Instant.parse("2026-11-01T10:15:30Z"));
        assertThat(mapped.callerRef()).isEqualTo(WorkItemApprovalPort.CALLER_REF_PREFIX + FP);
        assertThat(mapped.createdBy()).isEqualTo("agent:test");
        assertThat(mapped.candidateGroups()).isEqualTo("mlro");
        assertThat(mapped.labels()).contains(LABEL, WorkItemApprovalPort.USED_LABEL);

        server.respondNext(200, "{\"items\":[{\"id\":\"" + UUID.randomUUID()
                + "\",\"status\":\"SOMETHING_NEW\",\"extra\":1}],\"totalCount\":1,\"hasMore\":false}");
        assertThat(gateway.findByLabel(LABEL, "A").get(0).status()).isNull();
    }

    @Test
    void create_postsRequestAndMaps201() {
        WorkItemCreateRequest request = WorkItemCreateRequest.builder()
                .title("Account freeze")
                .expiresAt(Instant.parse("2026-12-01T00:00:00Z"))
                .tenancyId("A")
                .createdBy("agent:treasurer")
                .callerRef(WorkItemApprovalPort.CALLER_REF_PREFIX + FP)
                .labels(List.of(new WorkItemLabelRequest(LABEL, LabelPersistence.MANUAL, WorkItemApprovalPort.APPLIED_BY)))
                .build();

        GateItem created = gateway.create(request, "A");

        WorkItemCreateRequest sent = work.created.get(0);
        assertThat(sent.callerRef).isEqualTo(WorkItemApprovalPort.CALLER_REF_PREFIX + FP);
        assertThat(sent.expiresAt).isEqualTo(Instant.parse("2026-12-01T00:00:00Z"));
        assertThat(sent.labels).extracting(WorkItemLabelRequest::path).containsExactly(LABEL);
        assertThat(created.id()).isEqualTo(work.items.keySet().iterator().next());
        assertThat(created.status()).isEqualTo(WorkItemStatus.PENDING);
    }

    @Test
    void addLabel_sendsDecodedQueryValues() {
        var item = work.put(LABEL, WorkItemStatus.COMPLETED, Instant.now());

        gateway.addLabel(item.id(), WorkItemApprovalPort.USED_LABEL, WorkItemApprovalPort.APPLIED_BY, "A");

        assertThat(work.items.get(item.id()).labels()).anySatisfy(l -> {
            assertThat(l.path()).isEqualTo("casehub-lc4j/gate/used");
            assertThat(l.appliedBy()).isEqualTo("system:casehub-langchain4j");
        });
    }

    @Test
    void bearerTokenSelectsTenant() {
        HttpWorkItemGateway both = new HttpWorkItemGateway(server.url(),
                tenant -> Optional.ofNullable(Map.of("A", "tA", "B", "tB").get(tenant)), Duration.ofSeconds(5));

        both.findByLabel(LABEL, "B");
        both.findByLabel(LABEL, "A");

        assertThat(work.listedTenants).containsExactly("B", "A");
    }

    @Test
    void noTokenForTenant_throwsWithoutRequest() {
        assertThatThrownBy(() -> gateway.findByLabel(LABEL, "B")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("B");
        assertThat(server.requests()).isEmpty();
        assertThat(WorkTokenProvider.single("A", "tA").token("B")).isEmpty();
    }

    @Test
    void serverError_throwsWithStatus() {
        server.respondNext(500, "boom tA");

        assertThatThrownBy(() -> gateway.findByLabel(LABEL, "A")).isInstanceOfSatisfying(WorkItemGatewayException.class,
                e -> {
                    assertThat(e.status()).isEqualTo(500);
                    assertThat(e.getMessage()).doesNotContain("tA");
                });
    }

    @Test
    void addLabel404_throws() {
        assertThatThrownBy(() -> gateway.addLabel(UUID.randomUUID(), WorkItemApprovalPort.USED_LABEL,
                WorkItemApprovalPort.APPLIED_BY, "A"))
                .isInstanceOfSatisfying(WorkItemGatewayException.class, e -> assertThat(e.status()).isEqualTo(404));
    }

    @Test
    void slowResponse_timesOut() {
        HttpWorkItemGateway impatient = new HttpWorkItemGateway(server.url(), WorkTokenProvider.single("A", "tA"),
                Duration.ofMillis(200));
        server.delayNext(Duration.ofSeconds(1));

        assertThatThrownBy(() -> impatient.findByLabel(LABEL, "A"))
                .isInstanceOfSatisfying(WorkItemGatewayException.class, e -> assertThat(e.status()).isEqualTo(-1));
    }

    @Test
    void baseUrlWithTrailingSlash_works() {
        HttpWorkItemGateway slash = new HttpWorkItemGateway(java.net.URI.create(server.url() + "/"), WorkTokenProvider.single("A", "tA"),
                Duration.ofSeconds(5));
        work.put(LABEL, WorkItemStatus.PENDING, Instant.now());

        assertThat(slash.findByLabel(LABEL, "A")).hasSize(1);
    }

    @Test
    void singleRejectsBlank() {
        assertThatThrownBy(() -> WorkTokenProvider.single(" ", "t")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WorkTokenProvider.single("A", null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void findByLabel_serverThatNeverEndsPaging_isRefused() {
        String page = "{\"items\":[{\"id\":\"" + UUID.randomUUID() + "\",\"status\":\"PENDING\"}],\"totalCount\":1,\"hasMore\":true}";
        for (int i = 0; i <= HttpWorkItemGateway.MAX_PAGES; i++) {
            server.respondNext(200, page);
        }

        assertThatThrownBy(() -> gateway.findByLabel(LABEL, "A"))
                .isInstanceOfSatisfying(WorkItemGatewayException.class, e -> assertThat(e.status()).isEqualTo(-1))
                .hasMessageContaining("pages");
        assertThat(server.requests()).hasSize(HttpWorkItemGateway.MAX_PAGES);
    }
}
