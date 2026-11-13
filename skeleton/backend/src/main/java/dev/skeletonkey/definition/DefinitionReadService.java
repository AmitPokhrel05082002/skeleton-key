package dev.skeletonkey.definition;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.skeletonkey.domain.model.AppDefinition;
import dev.skeletonkey.domain.model.AppInfo;
import dev.skeletonkey.domain.model.Entity;
import dev.skeletonkey.domain.model.Field;
import dev.skeletonkey.domain.model.Form;
import dev.skeletonkey.domain.model.Principal;
import dev.skeletonkey.domain.model.View;
import dev.skeletonkey.persistence.DefinitionRepository;
import dev.skeletonkey.persistence.DefinitionVersionRow;
import dev.skeletonkey.record.PublishedDefinitions;
import dev.skeletonkey.record.PublishedDefinitions.PublishedApp;
import dev.skeletonkey.web.Conflict;
import dev.skeletonkey.web.NotFound;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Read projections of a PUBLISHED definition for the UI (Req 16): the role-restricted document for authenticated
 * principals and the Public_Subset for anonymous callers. Pure projection; no business rules beyond visibility.
 */
@Service
public class DefinitionReadService {
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private final PublishedDefinitions published;
    private final DefinitionRepository repository;

    public DefinitionReadService(PublishedDefinitions published, DefinitionRepository repository) {
        this.published = published;
        this.repository = repository;
    }

    /** Req 16.1-16.3: latest PUBLISHED version, or the explicit one (404 unknown, 409 not PUBLISHED). */
    public JsonNode readRestricted(String appCode, Integer version, Principal caller) {
        PublishedApp app;
        if (version == null) {
            app = published.latestPublished(appCode)
                    .orElseThrow(() -> new NotFound("App " + appCode + " has no published definition"));
        } else {
            DefinitionVersionRow row = repository.findByAppAndVersion(appCode, version)
                    .orElseThrow(() -> new NotFound("Definition " + appCode + " version " + version + " not found"));
            if (row.isDraft()) {
                throw new Conflict("DEFINITION_NOT_PUBLISHED",
                        "Version " + version + " of " + appCode + " is " + row.status() + ", not PUBLISHED");
            }
            app = published.publishedVersion(appCode, version)
                    .orElseThrow(() -> new NotFound("Definition " + appCode + " version " + version + " not found"));
        }
        return restrict(app, caller);
    }

    /** Req 16.4-16.6: public forms of the highest PUBLISHED version with only the fields they expose. */
    public JsonNode readPublicSubset(String appCode) {
        PublishedApp app = published.latestPublished(appCode)
                .orElseThrow(() -> new NotFound("App " + appCode + " has no published definition"));
        AppDefinition def = app.definition();
        ObjectNode out = JSON.objectNode();
        out.put("version", app.version());
        out.set("app", appNode(def.app()));
        ArrayNode forms = out.putArray("forms");
        for (Form form : def.forms()) {
            if (!form.isPublic()) {
                continue;
            }
            Entity entity = def.entity(form.entity()).orElse(null);
            ObjectNode f = forms.addObject();
            f.put("code", form.code());
            putIfNotNull(f, "label", form.label());
            f.put("entity", form.entity());
            f.set("sections", sectionsNode(form));
            ArrayNode fields = f.putArray("fields");
            for (String code : exposedFields(form)) {
                if (entity != null) {
                    entity.field(code).ifPresent(fd -> fields.add(fieldNode(fd)));
                }
            }
        }
        return out;
    }

    private JsonNode restrict(PublishedApp published, Principal caller) {
        AppDefinition def = published.definition();
        List<Form> forms = def.forms().stream().filter(f -> allowed(f.roles(), caller)).toList();
        List<View> views = def.views().stream().filter(v -> allowed(v.roles(), caller)).toList();
        Set<String> entityCodes = new LinkedHashSet<>();
        forms.forEach(f -> entityCodes.add(f.entity()));
        views.forEach(v -> entityCodes.add(v.entity()));

        ObjectNode out = JSON.objectNode();
        out.put("schemaVersion", 1);
        out.put("version", published.version());
        out.set("app", appNode(def.app()));
        ArrayNode roles = out.putArray("roles");
        def.roles().forEach(r -> {
            ObjectNode n = roles.addObject();
            n.put("code", r.code());
            n.put("label", r.label());
            if (r.isPublic()) {
                n.put("public", true);
            }
        });
        ArrayNode entities = out.putArray("entities");
        def.entities().stream().filter(e -> entityCodes.contains(e.code())).forEach(e -> {
            ObjectNode n = entities.addObject();
            n.put("code", e.code());
            n.put("label", e.label());
            putIfNotNull(n, "titleField", e.titleField());
            putIfNotNull(n, "referencePrefix", e.referencePrefix());
            ArrayNode fields = n.putArray("fields");
            e.fields().forEach(fd -> fields.add(fieldNode(fd)));
        });
        ArrayNode formsOut = out.putArray("forms");
        forms.forEach(form -> {
            ObjectNode n = formsOut.addObject();
            n.put("code", form.code());
            putIfNotNull(n, "label", form.label());
            n.put("entity", form.entity());
            if (form.isPublic()) {
                n.put("public", true);
            }
            if (!form.roles().isEmpty()) {
                n.set("roles", strings(form.roles()));
            }
            putIfNotNull(n, "startsWorkflow", form.startsWorkflow());
            n.set("sections", sectionsNode(form));
        });
        ArrayNode viewsOut = out.putArray("views");
        views.forEach(v -> {
            ObjectNode n = viewsOut.addObject();
            n.put("code", v.code());
            putIfNotNull(n, "label", v.label());
            n.put("type", v.type().name());
            n.put("entity", v.entity());
            if (!v.roles().isEmpty()) {
                n.set("roles", strings(v.roles()));
            }
            n.set("columns", strings(v.columns()));
            n.set("filters", strings(v.filters()));
            if (v.defaultSort() != null) {
                ObjectNode s = n.putObject("defaultSort");
                s.put("field", v.defaultSort().field());
                if (v.defaultSort().direction() != null) {
                    s.put("direction", v.defaultSort().direction().name());
                }
            }
            if (v.scope() != null) {
                n.put("scope", v.scope().name());
            }
        });
        return out;
    }

    private static boolean allowed(List<String> roles, Principal caller) {
        return roles.isEmpty() || caller.hasAnyRole(roles);
    }

    private static List<String> exposedFields(Form form) {
        return form.sections().stream().flatMap(s -> s.fields().stream()).distinct().toList();
    }

    private static ObjectNode appNode(AppInfo app) {
        ObjectNode n = JSON.objectNode();
        n.put("code", app.code());
        n.put("name", app.name());
        putIfNotNull(n, "description", app.description());
        if (app.branding() != null) {
            ObjectNode b = n.putObject("branding");
            putIfNotNull(b, "primaryColor", app.branding().primaryColor());
            putIfNotNull(b, "logo", app.branding().logo());
            putIfNotNull(b, "theme", app.branding().theme());
        }
        return n;
    }

    private static ArrayNode sectionsNode(Form form) {
        ArrayNode sections = JSON.arrayNode();
        form.sections().forEach(s -> {
            ObjectNode n = sections.addObject();
            putIfNotNull(n, "title", s.title());
            n.put("columns", s.columns());
            n.set("fields", strings(s.fields()));
        });
        return sections;
    }

    private static ObjectNode fieldNode(Field f) {
        ObjectNode n = JSON.objectNode();
        n.put("code", f.code());
        n.put("label", f.label());
        n.put("type", f.type().name());
        n.put("required", f.required());
        putIfNotNull(n, "help", f.help());
        if (f.defaultValue() != null && !f.defaultValue().isNull()) {
            n.set("default", f.defaultValue());
        }
        if (!f.options().isEmpty()) {
            ArrayNode options = n.putArray("options");
            f.options().forEach(o -> {
                ObjectNode on = options.addObject();
                on.put("value", o.value());
                on.put("label", o.label());
            });
        }
        putIfNotNull(n, "ref", f.ref());
        if (f.min() != null) {
            n.put("min", f.min());
        }
        if (f.max() != null) {
            n.put("max", f.max());
        }
        if (f.maxLength() != null) {
            n.put("maxLength", f.maxLength());
        }
        putIfNotNull(n, "pattern", f.pattern());
        putIfNotNull(n, "accept", f.accept());
        if (f.maxFiles() != null) {
            n.put("maxFiles", f.maxFiles());
        }
        return n;
    }

    private static ArrayNode strings(List<String> values) {
        ArrayNode a = JSON.arrayNode();
        values.forEach(a::add);
        return a;
    }

    private static void putIfNotNull(ObjectNode n, String key, String value) {
        if (value != null) {
            n.put(key, value);
        }
    }
}
