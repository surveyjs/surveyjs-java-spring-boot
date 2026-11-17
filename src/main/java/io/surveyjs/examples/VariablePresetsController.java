package io.surveyjs.examples;

import io.surveyjs.integration.StoredJson;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * Variable Presets: GET and PUT /api/variable-presets/{formId}, the presets Creator's Preview tab offers.
 */
@RestController
public class VariablePresetsController {

    private final JdbcClient jdbc;
    private final StoredJson storedJson;

    public VariablePresetsController(JdbcClient jdbc, StoredJson storedJson) {
        this.jdbc = jdbc;
        this.storedJson = storedJson;
    }

    // #region sjs:variable-presets.server
    // GET /api/variable-presets/{formId} — return the stored preset list, or [] when none are saved
    @GetMapping("/api/variable-presets/{formId}")
    public ResponseEntity<String> loadPresets(@PathVariable String formId) {
        String presets = jdbc.sql("SELECT json FROM variable_presets WHERE key = ?").param(formId).query(String.class).optional().orElse("[]");
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(presets);   // as stored
    }

    // PUT /api/variable-presets/{formId} — store the variable presets as-is: it's one JSON document
    @PutMapping("/api/variable-presets/{formId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('edit-forms')")   // editing is an admin action: 403 otherwise
    public void savePresets(@PathVariable String formId, @RequestBody JsonNode presets) {
        // A JsonNode, not a DTO: the presets are stored as they arrived, {} stays {}
        storedJson.put("variable_presets", formId, presets);
    }
    // #endregion
}
