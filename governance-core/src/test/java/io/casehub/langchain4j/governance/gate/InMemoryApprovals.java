package io.casehub.langchain4j.governance.gate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Approval store for gate tests: requests by tenant + fingerprint, decisions set by the test. */
class InMemoryApprovals implements ApprovalPort {

    record Stored(UUID id, String tenant, String fingerprint, GatedToolCall call, RiskVerdict.Gate gate,
            ApprovalState state, boolean used, Instant createdAt) {}

    final Map<UUID, Stored> requests = new ConcurrentHashMap<>();
    final AtomicInteger calls = new AtomicInteger();
    volatile RuntimeException markUsedFailure;
    volatile long latestDelayMillis;

    @Override
    public Optional<ApprovalRequest> latest(String tenancyId, String fingerprint) {
        calls.incrementAndGet();
        sleep(latestDelayMillis);
        return requests.values().stream()
                .filter(r -> r.tenant().equals(tenancyId) && r.fingerprint().equals(fingerprint))
                .max(java.util.Comparator.comparing(Stored::createdAt))
                .map(InMemoryApprovals::view);
    }

    @Override
    public synchronized ApprovalRequest create(GatedToolCall call, String fingerprint, RiskVerdict.Gate gate) {
        calls.incrementAndGet();
        Instant createdAt = Instant.now().plusNanos(requests.size());
        Stored stored = new Stored(UUID.randomUUID(), call.tenancyId(), fingerprint, call, gate,
                ApprovalState.WAITING, false, createdAt);
        requests.put(stored.id(), stored);
        return view(stored);
    }

    @Override
    public void markUsed(ApprovalRequest request, String tenancyId) {
        calls.incrementAndGet();
        if (markUsedFailure != null) {
            throw markUsedFailure;
        }
        requests.computeIfPresent(request.id(), (id, r) -> withState(r, r.state(), true));
    }

    void decide(UUID id, ApprovalState state) {
        requests.computeIfPresent(id, (k, r) -> withState(r, state, r.used()));
    }

    List<Stored> all() {
        return new ArrayList<>(requests.values());
    }

    Stored only() {
        if (requests.size() != 1) {
            throw new AssertionError("expected exactly one request, found " + requests.size());
        }
        return requests.values().iterator().next();
    }

    private static Stored withState(Stored r, ApprovalState state, boolean used) {
        return new Stored(r.id(), r.tenant(), r.fingerprint(), r.call(), r.gate(), state, used, r.createdAt());
    }

    private static ApprovalRequest view(Stored r) {
        return new ApprovalRequest(r.id(), r.state(), r.used(), r.createdAt());
    }

    private static void sleep(long millis) {
        if (millis > 0) {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
