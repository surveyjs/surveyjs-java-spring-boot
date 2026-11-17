package io.surveyjs.examples;

import java.time.Duration;

import io.surveyjs.config.ConditionalOnSwitch;
import io.surveyjs.integration.StoredJson;
import io.surveyjs.integration.SurveyJsService;
import io.surveyjs.integration.SurveyJsService.ServiceAnswer;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Lint JSON Schemas: PUT /api/forms/{id} linted by the SurveyJS service before it is stored.
 *
 * <p>Replaces CreatorLoadSaveController when LINT_DEFINITIONS=true.
 */
@RestController
@ConditionalOnSwitch("surveyjs.lint-definitions")
public class LintDefinitionController {

    private final StoredJson storedJson;
    private final SurveyJsService surveyjs;

    public LintDefinitionController(StoredJson storedJson, SurveyJsService surveyjs) {
        this.storedJson = storedJson;
        this.surveyjs = surveyjs;
    }

    // #region sjs:lint-definition.server
    // PUT /api/forms/{id} — lint the definition from Creator before storing it
    @PutMapping("/api/forms/{id}")
    @PreAuthorize("hasAuthority('edit-forms')")
    public ResponseEntity<JsonNode> saveLintedForm(@PathVariable String id, @RequestBody JsonNode definition) {
        // A JsonNode keeps {} as {} (see Load and Save Form JSON Schemas)
        ServiceAnswer lint = surveyjs.post("/schema", definition, Duration.ofSeconds(10));
        JsonNode answer = lint.json();   // null when it isn't JSON: fails closed below
        if (lint.status() == 422 && answer != null && answer.has("errors") && answer.has("warnings")) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT).body(answer);   // errors: refuse the save, report them as they are
        }
        boolean clean = lint.status() == 200 && answer instanceof ObjectNode found && found.isEmpty();
        boolean warningsOnly = lint.status() == 200 && answer instanceof ObjectNode found && found.size() == 1 && found.has("warnings");
        if (!clean && !warningsOnly) {   // fail closed: nothing else is a lint result
            throw surveyjs.unexpected("/schema", lint);
        }

        storedJson.put("forms", id, definition);   // stored as it arrived: 1.0 stays 1.0
        return warningsOnly ? ResponseEntity.ok(answer) : ResponseEntity.noContent().build();   // warnings don't block it
    }
    // #endregion
}
