package dev.skeletonkey.web;

import dev.skeletonkey.domain.model.Principal;
import dev.skeletonkey.record.RecordItem;
import dev.skeletonkey.record.RecordItemAssembler;
import dev.skeletonkey.record.RecordItemPage;
import dev.skeletonkey.record.RecordService;
import org.springframework.web.bind.annotation.RequestParam;
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
    private final RecordItemAssembler assembler;

    public RecordController(RecordService service, RecordItemAssembler assembler) {
        this.service = service;
        this.assembler = assembler;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RecordItem create(@PathVariable String app, @PathVariable String entity,
                             @RequestBody Map<String, Object> body) {
        return assembler.item(service.create(app, entity, body, caller()));
    }

    /** Paged list; every query parameter other than page, size, sort and view is an equality filter. */
    @GetMapping
    public RecordItemPage list(@PathVariable String app, @PathVariable String entity,
                           @RequestParam Map<String, String> params) {
        return assembler.page(service.list(app, entity, params, caller()));
    }

    @GetMapping("/{id}")
    public RecordItem read(@PathVariable String app, @PathVariable String entity, @PathVariable UUID id) {
        return assembler.item(service.read(app, entity, id, caller()));
    }

    @PutMapping("/{id}")
    public RecordItem replace(@PathVariable String app, @PathVariable String entity, @PathVariable UUID id,
                              @RequestBody Map<String, Object> body) {
        return assembler.item(service.update(app, entity, id, body, true, caller()));
    }

    @PatchMapping("/{id}")
    public RecordItem patch(@PathVariable String app, @PathVariable String entity, @PathVariable UUID id,
                            @RequestBody Map<String, Object> body) {
        return assembler.item(service.update(app, entity, id, body, false, caller()));
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
