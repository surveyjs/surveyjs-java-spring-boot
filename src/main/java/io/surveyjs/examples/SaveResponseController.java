package io.surveyjs.examples;

import java.util.Map;

import io.surveyjs.config.ConditionalOnSwitch;
import io.surveyjs.integration.StoredJson;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

/**
 * Save Responses: POST /api/responses stores submitted answers as one JSON document.
 *
 * <p>ValidateResponseController replaces this controller when VALIDATE_RESPONSES=true.
 */
@RestController
@ConditionalOnSwitch(value = "surveyjs.validate-responses", on = false)
public class SaveResponseController {

    private final StoredJson storedJson;

    public SaveResponseController(StoredJson storedJson) {
        this.storedJson = storedJson;
    }

    // #region sjs:save-response.server
    // POST /api/responses: Store submitted answers as one JSON document
    @PostMapping("/api/responses")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Integer> saveResponse(@RequestBody JsonNode body) {
        // A JsonNode, not a DTO: binding to a class would drop unknown keys and coerce values in the answers
        if (!body.path("formId").isString() || !body.has("data")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Expected { formId, data }");
        }

        int id = storedJson.insertResponse(body.get("formId").asString(), body.get("data"));   // stored as it arrived: {} stays {}, 1.0 stays 1.0

        return Map.of("id", id);
    }
    // #endregion
}
