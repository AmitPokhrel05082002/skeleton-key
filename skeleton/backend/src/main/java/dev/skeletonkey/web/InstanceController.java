package dev.skeletonkey.web;

import dev.skeletonkey.app.InstanceQueryService;
import dev.skeletonkey.app.InstanceView;
import dev.skeletonkey.app.HistoryEntryView;
import dev.skeletonkey.domain.model.Principal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Workflow instance endpoints (Req 9.1, 12.5-12.7, 15). History is read-only: only GET is mapped, so PUT, PATCH,
 * DELETE and POST on it end as 405 NOT_SUPPORTED. No business logic here.
 */
@RestController
@RequestMapping("/api/instances/{id}")
public class InstanceController {

    /** Body of an action request; every part is optional. */
    public record ActionBody(Map<String, Object> fields, String remarks, String attachment) {}

    private final InstanceQueryService service;

    public InstanceController(InstanceQueryService service) {
        this.service = service;
    }

    @GetMapping
    public InstanceView view(@PathVariable UUID id) {
        return service.view(id, caller());
    }

    @PostMapping("/actions/{code}")
    public InstanceView act(@PathVariable UUID id, @PathVariable String code,
                            @RequestBody(required = false) ActionBody body) {
        ActionBody b = body == null ? new ActionBody(null, null, null) : body;
        return service.execute(id, code, b.fields(), b.remarks(), b.attachment(), caller());
    }

    @GetMapping("/history")
    public List<HistoryEntryView> history(@PathVariable UUID id) {
        return service.history(id, caller());
    }

    private static Principal caller() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Principal p) {
            return p;
        }
        throw new InsufficientAuthenticationException("Authentication required");
    }
}
