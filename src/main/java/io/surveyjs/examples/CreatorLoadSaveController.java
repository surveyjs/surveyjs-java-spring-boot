package io.surveyjs.examples;

import io.surveyjs.config.ConditionalOnSwitch;
import io.surveyjs.integration.StoredJson;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * Load and Save Form JSON Schemas: PUT /api/forms/{id} stores a definition from Survey Creator.
 *
 * <p>GET /api/forms/{id} is in LoadDefinitionVariablesDataController: Creator reads its .definition. This controller
 * holds only the PUT, so LintDefinitionController can replace it when LINT_DEFINITIONS=true.
 */
@RestController
@ConditionalOnSwitch(value = "surveyjs.lint-definitions", on = false)
public class CreatorLoadSaveController {

    private final StoredJson storedJson;

    public CreatorLoadSaveController(StoredJson storedJson) {
        this.storedJson = storedJson;
    }

    // #region sjs:creator-load-save.server
    // PUT /api/forms/{id} — store the definition as-is: it's one JSON document
    @PutMapping("/api/forms/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('edit-forms')")   // editing is an admin action: 403 otherwise
    public void saveForm(@PathVariable String id, @RequestBody JsonNode definition) {
        // A JsonNode, not a DTO: binding to a class would drop the keys it doesn't declare
        storedJson.put("forms", id, definition);   // stored as it arrived: {} stays {}, 1.0 stays 1.0
        // 204: the next page load renders the new version
    }
    // #endregion
}
