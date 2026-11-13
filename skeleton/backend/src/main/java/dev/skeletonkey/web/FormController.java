package dev.skeletonkey.web;

import dev.skeletonkey.app.FormSubmissionService;
import dev.skeletonkey.domain.model.Principal;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Form submit endpoint (Req 6.5, 6.6, 8.1). The route is anonymous; the service decides from the form definition. */
@RestController
@RequestMapping("/api/apps/{app}/forms/{form}")
public class FormController {

    private final FormSubmissionService service;

    public FormController(FormSubmissionService service) {
        this.service = service;
    }

    @PostMapping("/submit")
    @ResponseStatus(HttpStatus.CREATED)
    public FormSubmissionService.Result submit(@PathVariable String app, @PathVariable String form,
                                               @RequestBody Map<String, Object> body) {
        return service.submit(app, form, body, optionalCaller());
    }

    /** The authenticated Principal, or null for anonymous callers (no token, or an invalid one). */
    private static Principal optionalCaller() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getPrincipal() instanceof Principal p ? p : null;
    }
}
