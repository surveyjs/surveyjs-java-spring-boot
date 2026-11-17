package io.surveyjs.examples;

import java.time.Duration;
import java.util.Map;

import io.surveyjs.config.ConditionalOnSwitch;
import io.surveyjs.integration.StoredJson;
import io.surveyjs.integration.SurveyJsService;
import io.surveyjs.integration.SurveyJsService.ServiceAnswer;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Validate Responses: POST /api/responses validated by the SurveyJS service before it is stored.
 *
 * <p>Replaces SaveResponseController when VALIDATE_RESPONSES=true. The service is SurveyJS Server, next to this app:
 * see docker-compose.yml and SURVEYJS_SERVICE_URL.
 */
@RestController
@ConditionalOnSwitch("surveyjs.validate-responses")
public class ValidateResponseController {

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final StoredJson storedJson;
    private final SurveyJsService surveyjs;

    public ValidateResponseController(JdbcClient jdbc, JsonMapper json, StoredJson storedJson, SurveyJsService surveyjs) {
        this.jdbc = jdbc;
        this.json = json;
        this.storedJson = storedJson;
        this.surveyjs = surveyjs;
    }

    // #region sjs:validate-response.server
    // POST /api/responses — ask the SurveyJS service to validate, then store as usual
    @PostMapping("/api/responses")
    public ResponseEntity<Map<String, Object>> saveValidatedResponse(@RequestBody JsonNode body) {
        // A JsonNode, not a DTO: the stored answers keep every key, {} and 1.0 (see Save Responses)
        if (!body.path("formId").isString() || !body.path("data").isObject()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Expected { formId, data } with an answers object");
        }
        String form = jdbc.sql("SELECT json FROM forms WHERE key = ?").param(body.get("formId").asString()).query(String.class).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown form"));
        ServiceAnswer check = surveyjs.post("/response", Map.of("schema", json.readTree(form), "response", body.get("data")), Duration.ofSeconds(10));
        JsonNode answer = check.json();   // null when it isn't JSON: fails closed below
        if (check.status() == 422 && answer != null && answer.has("errors")) {
            if (answer.has("warnings")) {   // the stored definition itself has errors
                throw surveyjs.unexpected("/response", check, "The stored definition has errors");
            }
            return ResponseEntity.badRequest().body(Map.of("errors", answer.get("errors")));   // the answers don't fit the definition
        }
        if (check.status() != 200 || !(answer instanceof ObjectNode valid && valid.isEmpty())) {   // fail closed: only exactly {} means valid
            throw surveyjs.unexpected("/response", check);
        }
        int id = storedJson.insertResponse(body.get("formId").asString(), body.get("data"));   // stored as it arrived: 1.0 stays 1.0
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", id));
    }
    // #endregion
}
