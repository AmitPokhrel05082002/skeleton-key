package dev.skeletonkey.app;

import dev.skeletonkey.definition.DefinitionQueryService;
import dev.skeletonkey.domain.model.Role;
import dev.skeletonkey.record.PublishedDefinitions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The roles an admin may give a user: the non-public roles of the latest PUBLISHED definition of every app, plus the
 * platform admin role. The same set drives the role picker and the validation (Req 6).
 */
@Service
public class RoleOptionsService {

    /** One picker entry. {@code apps} is empty for the platform admin role. */
    public record RoleOption(String code, String label, List<String> apps) {}

    static final String ADMIN_LABEL = "Platform administrator";

    private final DefinitionQueryService apps;
    private final PublishedDefinitions published;
    private final AdminGuard admin;

    public RoleOptionsService(DefinitionQueryService apps, PublishedDefinitions published, AdminGuard admin) {
        this.apps = apps;
        this.published = published;
        this.admin = admin;
    }

    @Transactional(readOnly = true)
    public List<RoleOption> options() {
        Map<String, String> labels = new TreeMap<>();
        Map<String, TreeSet<String>> appsByRole = new TreeMap<>();
        List<String> appCodes = new ArrayList<>(apps.appCodes());
        appCodes.sort(String::compareTo);
        for (String app : appCodes) {
            published.latestPublished(app).ifPresent(p -> {
                for (Role role : p.definition().roles()) {
                    if (role.isPublic()) {
                        continue;
                    }
                    // Apps are visited in code order, so the first label seen is the one of the smallest app code.
                    labels.putIfAbsent(role.code(), role.label());
                    appsByRole.computeIfAbsent(role.code(), k -> new TreeSet<>()).add(app);
                }
            });
        }
        // The admin role always exists; if an app also declares the same code, the app list is kept.
        labels.putIfAbsent(admin.adminRole(), ADMIN_LABEL);
        appsByRole.computeIfAbsent(admin.adminRole(), k -> new TreeSet<>());
        List<RoleOption> out = new ArrayList<>();
        for (Map.Entry<String, String> e : labels.entrySet()) {
            out.add(new RoleOption(e.getKey(), e.getValue(), List.copyOf(appsByRole.get(e.getKey()))));
        }
        return out;
    }

    /** The codes of {@link #options()}. */
    @Transactional(readOnly = true)
    public Set<String> assignableCodes() {
        Set<String> codes = new TreeSet<>();
        options().forEach(o -> codes.add(o.code()));
        return codes;
    }
}
