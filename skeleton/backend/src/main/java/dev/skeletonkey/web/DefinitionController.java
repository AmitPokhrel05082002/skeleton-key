package dev.skeletonkey.web;

import com.fasterxml.jackson.databind.JsonNode;
import dev.skeletonkey.app.AdminGuard;
import dev.skeletonkey.definition.DefinitionQueryService;
import dev.skeletonkey.definition.DefinitionService;
import dev.skeletonkey.definition.DefinitionVersionRef;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Definition management: submit, edit, read and publish whole-document versions (Req 1, 2, 5). Every endpoint here
 * requires the Platform_Admin role (Req 6.9), checked by {@link AdminGuard}. The body is taken as raw text so
 * malformed JSON is reported with line and column by the loader. No business logic here.
 */
@RestController
@RequestMapping("/api")
public class DefinitionController {
    private final DefinitionService service;
    private final DefinitionQueryService queries;
    private final AdminGuard admin;

    public DefinitionController(DefinitionService service, DefinitionQueryService queries, AdminGuard admin) {
        this.service = service;
        this.queries = queries;
        this.admin = admin;
    }

    @PostMapping("/definitions")
    @ResponseStatus(HttpStatus.CREATED)
    public DefinitionVersionRef submit(@RequestBody String json) {
        admin.require(RecordController.caller());
        return service.submit(json);
    }

    /** Stores the edit as a new DRAFT based on {@code base}; the base version is untouched. */
    @PostMapping("/apps/{app}/definitions/{base}/edit")
    @ResponseStatus(HttpStatus.CREATED)
    public DefinitionVersionRef edit(@PathVariable String app, @PathVariable int base, @RequestBody String json) {
        admin.require(RecordController.caller());
        return service.edit(app, base, json);
    }

    /** 409 for a PUBLISHED version; a DRAFT is superseded by a new DRAFT. */
    @PutMapping("/definitions/{id}")
    @ResponseStatus(HttpStatus.CREATED)
    public DefinitionVersionRef update(@PathVariable UUID id, @RequestBody String json) {
        admin.require(RecordController.caller());
        return service.update(id, json);
    }

    @GetMapping("/definitions/{id}")
    public JsonNode read(@PathVariable UUID id) {
        admin.require(RecordController.caller());
        return service.read(id);
    }

    @GetMapping("/definitions/{id}/info")
    public DefinitionVersionRef info(@PathVariable UUID id) {
        admin.require(RecordController.caller());
        return service.describe(id);
    }

    @GetMapping("/definitions/apps")
    @Operation(summary = "App codes that have at least one stored version", security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponse(responseCode = "200", description = "App codes ordered by code point",
            content = @Content(array = @ArraySchema(schema = @Schema(implementation = String.class))))
    @ApiResponse(responseCode = "401", description = "Authentication required",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "403", description = "Platform admin role required",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public List<String> apps() {
        admin.require(RecordController.caller());
        return queries.appCodes();
    }

    @GetMapping("/apps/{app}/definitions")
    @Operation(summary = "Every stored version of an app, highest first", security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponse(responseCode = "200", description = "Versions without their documents",
            content = @Content(array = @ArraySchema(schema = @Schema(implementation = DefinitionVersionRef.class))))
    @ApiResponse(responseCode = "401", description = "Authentication required",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "403", description = "Platform admin role required",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "404", description = "Unknown or malformed app code",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public List<DefinitionVersionRef> versions(@PathVariable String app) {
        admin.require(RecordController.caller());
        return queries.versions(app);
    }

    /** Runs the publish-time checks on the posted text and stores nothing. The admin check comes before the body is read. */
    @PostMapping("/definitions/validate")
    @Operation(summary = "Validate a definition without storing it", security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponse(responseCode = "200", description = "Every violation, or an empty array",
            content = @Content(array = @ArraySchema(schema = @Schema(implementation = ApiViolation.class))))
    @ApiResponse(responseCode = "400", description = "Not parseable JSON (with line and column) or not an object",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "401", description = "Authentication required",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "403", description = "Platform admin role required",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "413", description = "Body larger than 1 MiB",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public List<ApiViolation> validate(HttpServletRequest request) throws IOException {
        admin.require(RecordController.caller());
        return service.validateText(BoundedBody.read(request));
    }

    @PostMapping("/definitions/{id}/publish")
    public DefinitionVersionRef publish(@PathVariable UUID id) {
        return service.publish(id, admin.require(RecordController.caller()));
    }
}
