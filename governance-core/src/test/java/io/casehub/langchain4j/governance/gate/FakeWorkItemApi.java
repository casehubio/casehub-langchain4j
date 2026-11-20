package io.casehub.langchain4j.governance.gate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.casehub.work.api.LabelPersistence;
import io.casehub.work.api.WorkItemCreateRequest;
import io.casehub.work.api.WorkItemStatus;
import io.casehub.work.api.spi.WorkItemApi;
import io.casehub.work.api.view.WorkItemLabelView;
import io.casehub.work.api.view.WorkItemPage;
import io.casehub.work.api.view.WorkItemView;
import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** In-memory stand-in for the few {@link WorkItemApi} calls the gate uses. */
public final class FakeWorkItemApi {

    public final Map<UUID, WorkItemView> items = new ConcurrentHashMap<>();
    public final List<WorkItemCreateRequest> created = new CopyOnWriteArrayList<>();
    public final List<String> listedTenants = new CopyOnWriteArrayList<>();
    public final WorkItemApi api = mock(WorkItemApi.class);

    public FakeWorkItemApi() {
        when(api.create(any(), any())).thenAnswer(inv -> {
            WorkItemCreateRequest request = inv.getArgument(0);
            created.add(request);
            List<WorkItemLabelView> labels = new ArrayList<>();
            if (request.labels != null) {
                request.labels.forEach(l -> labels.add(new WorkItemLabelView(l.path(), l.persistence(), l.appliedBy())));
            }
            WorkItemView view = view(UUID.randomUUID(), WorkItemStatus.PENDING, labels, Instant.now(),
                    request.callerRef, request.createdBy, request.candidateGroups);
            items.put(view.id(), view);
            return view;
        });
        when(api.listAll(isNull(), isNull(), anyString(), isNull(), any(), any(), any())).thenAnswer(inv -> {
            String label = inv.getArgument(2);
            listedTenants.add(inv.getArgument(4));
            // DefaultWorkItemApi gives no order; oldest-first is the worst case for "newest" lookups.
            List<WorkItemView> found = items.values().stream()
                    .filter(v -> v.labels().stream().anyMatch(l -> l.path().equals(label)))
                    .sorted(java.util.Comparator.comparing(WorkItemView::createdAt))
                    .toList();
            int offset = inv.getArgument(5) == null ? 0 : inv.<Integer>getArgument(5);
            int limit = inv.getArgument(6) == null ? found.size() : inv.<Integer>getArgument(6);
            int end = Math.min(found.size(), offset + limit);
            List<WorkItemView> page = offset >= found.size() ? List.of() : found.subList(offset, end);
            return new WorkItemPage(page, found.size(), end < found.size());
        });
        when(api.addLabel(any(), anyString(), anyString(), any())).thenAnswer(inv -> {
            UUID id = inv.getArgument(0);
            WorkItemView old = items.get(id);
            List<WorkItemLabelView> labels = new ArrayList<>(old.labels());
            labels.add(new WorkItemLabelView(inv.getArgument(1), LabelPersistence.MANUAL, inv.getArgument(2)));
            WorkItemView updated = view(id, old.status(), labels, old.createdAt(), old.callerRef(), old.createdBy(),
                    old.candidateGroups());
            items.put(id, updated);
            return updated;
        });
    }

    /** Puts a genuine gate request (callerRef, agent creator, group "mlro") straight into the store. */
    public WorkItemView put(String label, WorkItemStatus status, Instant createdAt, String... extraLabels) {
        String fingerprint = label.substring(label.lastIndexOf('/') + 1);
        return putWithProvenance(label, status, createdAt, "casehub-lc4j:gate:" + fingerprint, "agent:test", "mlro",
                extraLabels);
    }

    /** Puts an item with full control over the provenance fields. */
    public WorkItemView putWithProvenance(String label, WorkItemStatus status, Instant createdAt, String callerRef,
            String createdBy, String candidateGroups, String... extraLabels) {
        List<WorkItemLabelView> labels = new ArrayList<>();
        labels.add(new WorkItemLabelView(label, LabelPersistence.MANUAL, "test"));
        for (String extra : extraLabels) {
            labels.add(new WorkItemLabelView(extra, LabelPersistence.MANUAL, "test"));
        }
        WorkItemView view = view(UUID.randomUUID(), status, labels, createdAt, callerRef, createdBy, candidateGroups);
        items.put(view.id(), view);
        return view;
    }

    public void setStatus(UUID id, WorkItemStatus status) {
        WorkItemView old = items.get(id);
        items.put(id, view(id, status, old.labels(), old.createdAt(), old.callerRef(), old.createdBy(),
                old.candidateGroups()));
    }

    /** Builds a WorkItemView with every component null except the ones the gate reads. */
    static WorkItemView view(UUID id, WorkItemStatus status, List<WorkItemLabelView> labels, Instant createdAt,
            String callerRef, String createdBy, String candidateGroups) {
        try {
            RecordComponent[] components = WorkItemView.class.getRecordComponents();
            Class<?>[] types = new Class<?>[components.length];
            Object[] args = new Object[components.length];
            for (int i = 0; i < components.length; i++) {
                types[i] = components[i].getType();
                args[i] = switch (components[i].getName()) {
                    case "id" -> id;
                    case "status" -> status;
                    case "labels" -> List.copyOf(labels);
                    case "createdAt" -> createdAt;
                    case "callerRef" -> callerRef;
                    case "createdBy" -> createdBy;
                    case "candidateGroups" -> candidateGroups;
                    default -> types[i] == boolean.class ? false : types[i].isPrimitive() ? 0 : null;
                };
            }
            Constructor<WorkItemView> constructor = WorkItemView.class.getDeclaredConstructor(types);
            return constructor.newInstance(args);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
