package io.casehub.langchain4j.governance.gate;

import io.casehub.work.api.WorkItemCreateRequest;
import io.casehub.work.api.spi.WorkItemApi;
import io.casehub.work.api.view.WorkItemLabelView;
import io.casehub.work.api.view.WorkItemPage;
import io.casehub.work.api.view.WorkItemView;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** {@link WorkItemGateway} over an in-process casehub-work {@link WorkItemApi}. */
public final class WorkItemApiGateway implements WorkItemGateway {

    private static final int PAGE = 50;

    private final WorkItemApi api;

    public WorkItemApiGateway(WorkItemApi api) {
        this.api = Objects.requireNonNull(api, "api required");
    }

    @Override
    public List<GateItem> findByLabel(String label, String tenancyId) {
        List<GateItem> items = new ArrayList<>();
        int offset = 0;
        while (true) {
            WorkItemPage page = api.listAll(null, null, label, null, tenancyId, offset, PAGE);
            page.items().forEach(item -> items.add(toGateItem(item)));
            if (!page.hasMore() || page.items().isEmpty()) {
                return items;
            }
            offset += page.items().size();
        }
    }

    @Override
    public GateItem create(WorkItemCreateRequest request, String tenancyId) {
        return toGateItem(api.create(request, tenancyId));
    }

    @Override
    public void addLabel(UUID id, String path, String appliedBy, String tenancyId) {
        api.addLabel(id, path, appliedBy, tenancyId);
    }

    static GateItem toGateItem(WorkItemView view) {
        Set<String> labels = view.labels() == null ? Set.of()
                : view.labels().stream().map(WorkItemLabelView::path).filter(Objects::nonNull).collect(Collectors.toSet());
        return new GateItem(view.id(), view.status(), view.createdBy(), view.callerRef(), view.createdAt(), labels,
                view.candidateGroups());
    }
}
