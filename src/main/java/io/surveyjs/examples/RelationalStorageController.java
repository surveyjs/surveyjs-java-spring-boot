package io.surveyjs.examples;

import java.util.Map;

import io.surveyjs.integration.StoredJson;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

/**
 * Use Relational Storage: POST /api/claims keeps the original JSON and its version, then copies the stable answer
 * keys (valueName) into the claims columns, in one transaction.
 */
@RestController
public class RelationalStorageController {

    private final JdbcClient jdbc;
    private final StoredJson storedJson;

    public RelationalStorageController(JdbcClient jdbc, StoredJson storedJson) {
        this.jdbc = jdbc;
        this.storedJson = storedJson;
    }

    // #region sjs:relational-storage.server
    // POST /api/claims — original JSON first, then the columns you query
    @PostMapping("/api/claims")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Map<String, Integer> saveClaim(@RequestBody JsonNode body) {
        // A JsonNode, not a DTO: the stored answers keep every key, {} and 1.0 as they arrived
        if (!body.hasNonNull("data") || !body.hasNonNull("definitionVersion")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Expected { data, definitionVersion }");
        }
        JsonNode data = body.get("data");

        int id = storedJson.insertResponse("claim", data, StoredJson.text(body.get("definitionVersion")));   // the original and its version
        jdbc.sql("INSERT INTO claims (response_id, customer_email, amount) VALUES (?, ?, ?)")   // keys come from valueName
                .params(id, StoredJson.text(data.get("customer_email")), number(data.get("amount"))).update();

        return Map.of("id", id);
    }
    // #endregion

    private static Double number(JsonNode value) {
        return value != null && value.isNumber() ? value.asDouble() : null;
    }
}
