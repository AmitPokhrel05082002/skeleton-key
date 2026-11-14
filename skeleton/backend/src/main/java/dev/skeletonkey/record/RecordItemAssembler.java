package dev.skeletonkey.record;

import dev.skeletonkey.domain.model.InstanceState;
import dev.skeletonkey.domain.model.Workflow;
import dev.skeletonkey.persistence.InstanceRepository;
import dev.skeletonkey.persistence.UserRepository;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Adds the workflow summary to records already returned by {@link RecordService}. Visibility is decided there;
 * this class only decorates. A page costs one instance lookup, at most one user lookup, and one definition lookup
 * per distinct pinned (app, version), independent of the page size.
 */
@Service
public class RecordItemAssembler {
    private final InstanceRepository instances;
    private final UserRepository users;
    private final PublishedDefinitions definitions;

    public RecordItemAssembler(InstanceRepository instances, UserRepository users, PublishedDefinitions definitions) {
        this.instances = instances;
        this.users = users;
        this.definitions = definitions;
    }

    public RecordItem item(RecordView view) {
        return assemble(List.of(view)).get(0);
    }

    public RecordItemPage page(RecordPage page) {
        return new RecordItemPage(assemble(page.items()), page.page(), page.size(), page.total(), page.sort());
    }

    private List<RecordItem> assemble(List<RecordView> views) {
        if (views.isEmpty()) {
            return List.of();
        }
        Map<UUID, InstanceState> byRecord = instances.findByRecordIds(views.stream().map(RecordView::id).toList());

        Set<UUID> assigneeIds = new LinkedHashSet<>();
        Map<String, Optional<PublishedDefinitions.PublishedApp>> apps = new HashMap<>();
        for (InstanceState s : byRecord.values()) {
            if (!s.ended() && s.assigneeId() != null) {
                assigneeIds.add(s.assigneeId());
            }
            apps.computeIfAbsent(s.appCode() + "\u0000" + s.defVersion(),
                    k -> definitions.publishedVersion(s.appCode(), s.defVersion()));
        }
        Map<UUID, String> names = assigneeIds.isEmpty() ? Map.of() : users.findDisplayNames(assigneeIds);

        return views.stream().map(v -> {
            InstanceState s = byRecord.get(v.id());
            Workflow pinned = s == null ? null : apps.get(s.appCode() + "\u0000" + s.defVersion())
                    .flatMap(a -> a.definition().workflow(s.workflowCode())).orElse(null);
            return RecordItem.of(v, WorkflowSummary.of(s, pinned, names));
        }).toList();
    }
}
