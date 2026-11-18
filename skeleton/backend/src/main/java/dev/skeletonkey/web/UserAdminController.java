package dev.skeletonkey.web;

import dev.skeletonkey.app.AdminGuard;
import dev.skeletonkey.app.RoleOptionsService;
import dev.skeletonkey.app.UserAdminService;
import dev.skeletonkey.domain.model.Principal;
import dev.skeletonkey.persistence.UserRow;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * User administration for the platform admin. Every method starts with {@link AdminGuard#require}. No business logic
 * here; the service validates and the repository never returns a password hash to this layer.
 */
@RestController
@RequestMapping("/api/admin/users")
@SecurityRequirement(name = "bearerAuth")
public class UserAdminController {

    /** The only user shape the API returns: no password, no hash. */
    public record UserView(UUID id, String username, String displayName, boolean active, List<String> roles,
                           Instant createdAt) {
        static UserView of(UserRow r) {
            return new UserView(r.id(), r.username(), r.displayName(), r.active(),
                    r.roles().stream().sorted().toList(), r.createdAt());
        }
    }

    public record UserPageView(List<UserView> items, int page, int size, long total, String sort) {}

    public record CreateUserRequest(String username, String displayName, String password, List<String> roles,
                                    Boolean active) {}

    public record PasswordRequest(String password) {}

    private final UserAdminService service;
    private final AdminGuard admin;

    public UserAdminController(UserAdminService service, AdminGuard admin) {
        this.service = service;
        this.admin = admin;
    }

    @GetMapping
    @Operation(summary = "List users (paged, searchable)")
    @ApiResponse(responseCode = "200", description = "A page of users")
    @ApiResponse(responseCode = "403", description = "Platform admin role required",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public UserPageView list(@RequestParam(required = false) String q,
                             @RequestParam(required = false) String role,
                             @RequestParam(required = false) Boolean active,
                             @RequestParam(required = false) String page,
                             @RequestParam(required = false) String size,
                             @RequestParam(required = false) String sort) {
        admin.require(RecordController.caller());
        UserAdminService.UserPage p = service.search(q, role, active, page, size, sort);
        return new UserPageView(p.items().stream().map(UserView::of).toList(), p.page(), p.size(), p.total(),
                p.sort());
    }

    @GetMapping("/role-options")
    @Operation(summary = "Roles that can be given to a user")
    public List<RoleOptionsService.RoleOption> roleOptions() {
        admin.require(RecordController.caller());
        return service.roleOptions();
    }

    @GetMapping("/{id}")
    public UserView get(@PathVariable UUID id) {
        admin.require(RecordController.caller());
        return UserView.of(service.get(id));
    }

    @PostMapping
    @Operation(summary = "Create a user")
    public ResponseEntity<UserView> create(@RequestBody(required = false) CreateUserRequest body) {
        Principal actor = admin.require(RecordController.caller());
        if (body == null) {
            throw new BadRequest("Request body required", List.of());
        }
        UserRow created = service.create(new UserAdminService.CreateUser(body.username(), body.displayName(),
                body.password(), body.roles(), body.active()), actor);
        return ResponseEntity.created(URI.create("/api/admin/users/" + created.id())).body(UserView.of(created));
    }

    /** Partial update. The body is read as a map so an absent property differs from a null one. */
    @PatchMapping("/{id}")
    @Operation(summary = "Update display name, roles or active flag")
    public UserView update(@PathVariable UUID id, @RequestBody(required = false) Map<String, Object> body) {
        Principal actor = admin.require(RecordController.caller());
        return UserView.of(service.update(id, body == null ? new LinkedHashMap<>() : body, actor));
    }

    @PostMapping("/{id}/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Reset a user's password")
    public void resetPassword(@PathVariable UUID id, @RequestBody(required = false) PasswordRequest body) {
        Principal actor = admin.require(RecordController.caller());
        service.resetPassword(id, body == null ? null : body.password(), actor);
    }
}
