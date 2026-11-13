package dev.skeletonkey.web;

import com.fasterxml.jackson.databind.JsonNode;
import dev.skeletonkey.definition.DefinitionReadService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Definition reads for the UI (Req 16): the role-restricted document for authenticated callers and the anonymous
 * Public_Subset. Not the admin raw read. No business logic here.
 */
@RestController
@RequestMapping("/api")
public class DefinitionReadController {
    private final DefinitionReadService service;

    public DefinitionReadController(DefinitionReadService service) {
        this.service = service;
    }

    @GetMapping("/apps/{app}/definition")
    public JsonNode definition(@PathVariable String app, @RequestParam(required = false) Integer version) {
        return service.readRestricted(app, version, RecordController.caller());
    }

    @GetMapping("/public/apps/{app}")
    public JsonNode publicSubset(@PathVariable String app) {
        return service.readPublicSubset(app);
    }
}
