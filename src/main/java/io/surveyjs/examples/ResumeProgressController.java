package io.surveyjs.examples;

import io.surveyjs.integration.StoredJson;
import io.surveyjs.security.User;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

/**
 * Resume Progress: GET, PUT and DELETE /api/progress/{formId}, a draft per user and form.
 */
@RestController
public class ResumeProgressController {

    private final JdbcClient jdbc;
    private final StoredJson storedJson;

    public ResumeProgressController(JdbcClient jdbc, StoredJson storedJson) {
        this.jdbc = jdbc;
        this.storedJson = storedJson;
    }

    // #region sjs:resume-progress.server
    // Return the current user's saved answers and UI state, or null when there is no draft
    @GetMapping("/api/progress/{formId}")
    public ResponseEntity<String> loadProgress(@PathVariable String formId, @AuthenticationPrincipal User user) {
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in to load progress");
        }
        String draft = jdbc.sql("SELECT json FROM progress WHERE key = ?").param(user.id() + ":" + formId)
                .query(String.class).optional().orElse("null");
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(draft);   // as stored, or the JSON text null
    }

    // Store progress under a key containing both the user ID and form ID
    @PutMapping("/api/progress/{formId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void saveProgress(@PathVariable String formId, @RequestBody JsonNode body, @AuthenticationPrincipal User user) {
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in to save progress");
        }
        // A JsonNode, not a DTO: { data, uiState } is stored as it arrived, {} stays {}
        storedJson.put("progress", user.id() + ":" + formId, body);
    }
    // #endregion

    // #region sjs:resume-progress.delete
    // Remove only the current user's draft
    @DeleteMapping("/api/progress/{formId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clearProgress(@PathVariable String formId, @AuthenticationPrincipal User user) {
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in to clear progress");
        }
        jdbc.sql("DELETE FROM progress WHERE key = ?").param(user.id() + ":" + formId).update();
    }
    // #endregion
}
