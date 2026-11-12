package dev.skeletonkey.web;

import com.fasterxml.jackson.databind.JsonNode;
import dev.skeletonkey.definition.DefinitionService;
import dev.skeletonkey.definition.DefinitionVersionRef;
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
 * Definition management: submit, edit, read and publish whole-document versions (Req 1, 2, 5). The body is taken as
 * raw text so malformed JSON is reported with line and column by the loader. No business logic here.
 */
@RestController
@RequestMapping("/api")
public class DefinitionController {
    private final DefinitionService service;

    public DefinitionController(DefinitionService service) {
        this.service = service;
    }

    @PostMapping("/definitions")
    @ResponseStatus(HttpStatus.CREATED)
    public DefinitionVersionRef submit(@RequestBody String json) {
        return service.submit(json);
    }

    /** Stores the edit as a new DRAFT based on {@code base}; the base version is untouched. */
    @PostMapping("/apps/{app}/definitions/{base}/edit")
    @ResponseStatus(HttpStatus.CREATED)
    public DefinitionVersionRef edit(@PathVariable String app, @PathVariable int base, @RequestBody String json) {
        return service.edit(app, base, json);
    }

    /** 409 for a PUBLISHED version; a DRAFT is superseded by a new DRAFT. */
    @PutMapping("/definitions/{id}")
    @ResponseStatus(HttpStatus.CREATED)
    public DefinitionVersionRef update(@PathVariable UUID id, @RequestBody String json) {
        return service.update(id, json);
    }

    @GetMapping("/definitions/{id}")
    public JsonNode read(@PathVariable UUID id) {
        return service.read(id);
    }

    @GetMapping("/definitions/{id}/info")
    public DefinitionVersionRef info(@PathVariable UUID id) {
        return service.describe(id);
    }

    @PostMapping("/definitions/{id}/publish")
    public DefinitionVersionRef publish(@PathVariable UUID id) {
        return service.publish(id, RecordController.caller());
    }
}
