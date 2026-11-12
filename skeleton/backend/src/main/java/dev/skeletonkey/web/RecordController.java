package dev.skeletonkey.web;

import dev.skeletonkey.domain.model.Principal;
import dev.skeletonkey.record.RecordService;
import dev.skeletonkey.record.RecordView;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Generic records endpoints driven by the published definition (Req 7). No business logic here. */
@RestController
@RequestMapping("/api/apps/{app}/entities/{entity}/records")
public class RecordController {

    private final RecordService service;

    public RecordController(RecordService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RecordView create(@PathVariable String app, @PathVariable String entity,
                             @RequestBody Map<String, Object> body) {
        return service.create(app, entity, body, caller());
    }

    @GetMapping("/{id}")
    public RecordView read(@PathVariable String app, @PathVariable String entity, @PathVariable UUID id) {
        return service.read(app, entity, id, caller());
    }

    @PutMapping("/{id}")
    public RecordView replace(@PathVariable String app, @PathVariable String entity, @PathVariable UUID id,
                              @RequestBody Map<String, Object> body) {
        return service.update(app, entity, id, body, true, caller());
    }

    @PatchMapping("/{id}")
    public RecordView patch(@PathVariable String app, @PathVariable String entity, @PathVariable UUID id,
                            @RequestBody Map<String, Object> body) {
        return service.update(app, entity, id, body, false, caller());
    }

    /** The authenticated {@link Principal} (set by the JWT filter as the authentication principal), else 401. */
    static Principal caller() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Principal p) {
            return p;
        }
        throw new InsufficientAuthenticationException("Authentication required");
    }
}
