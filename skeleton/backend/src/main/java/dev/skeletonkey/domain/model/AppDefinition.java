package dev.skeletonkey.domain.model;

import java.util.List;
import java.util.Optional;

public record AppDefinition(AppInfo app, List<Role> roles, List<Entity> entities, List<Form> forms,
                            List<View> views, List<Workflow> workflows) {
    public AppDefinition {
        roles = List.copyOf(roles);
        entities = List.copyOf(entities);
        forms = forms == null ? List.of() : List.copyOf(forms);
        views = views == null ? List.of() : List.copyOf(views);
        workflows = List.copyOf(workflows);
    }

    public Optional<Entity> entity(String code) {
        return entities.stream().filter(e -> e.code().equals(code)).findFirst();
    }

    public Optional<Workflow> workflow(String code) {
        return workflows.stream().filter(w -> w.code().equals(code)).findFirst();
    }

    public Optional<Form> form(String code) {
        return forms.stream().filter(f -> f.code().equals(code)).findFirst();
    }

    public Optional<Role> publicRole() {
        return roles.stream().filter(Role::isPublic).findFirst();
    }
}
