package io.surveyjs.demo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import io.surveyjs.config.SurveyJsProperties;
import io.surveyjs.integration.TtlCache;
import io.surveyjs.relay.RoomKey;
import io.surveyjs.security.User;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Demo only: GET /demo/stored/{slug}, the Data panel ("What the server stored"). A fixed, whitelisted read per example,
 * never arbitrary SQL. The section names and row fields are the reference's ({@code App\Support\Demo::stored()}): the
 * contract test and the browser tests read them.
 */
@RestController
public class StoredController {

    private static final Pattern ROOM = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");
    private static final Pattern ID = Pattern.compile("^\\s*\\+?\\d{1,9}\\s*$");

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final TtlCache cache;
    private final RoomKey roomKey;
    private final SurveyJsProperties settings;

    public StoredController(JdbcClient jdbc, JsonMapper json, TtlCache cache, RoomKey roomKey, SurveyJsProperties settings) {
        this.jdbc = jdbc;
        this.json = json;
        this.cache = cache;
        this.roomKey = roomKey;
        this.settings = settings;
    }

    @GetMapping("/demo/stored/{slug}")
    public Map<String, Object> stored(@PathVariable String slug, @AuthenticationPrincipal User user,
                                      @RequestParam(name = "id", defaultValue = "") String rawId,
                                      @RequestParam(defaultValue = "") String key,
                                      @RequestParam(defaultValue = "") String room) {
        Integer id = ID.matcher(rawId).matches() && Integer.parseInt(rawId.strip().replace("+", "")) > 0
                ? Integer.parseInt(rawId.strip().replace("+", "")) : null;
        String email = user == null ? "" : user.email();
        Map<String, Object> sections = new LinkedHashMap<>();
        switch (slug) {
            case "save-response" -> sections.put("responses", responses(id == null ? "contact" : null, id, 5, null));
            case "load-definition-variables-data" -> sections.put("responses", responses("claim", id, 5, id == null ? email : null));
            case "resume-progress" -> {
                sections.put("progress", document("progress", (user == null ? "" : user.id()) + ":" + (key.isEmpty() ? "onboarding" : key)));
                sections.put("responses", responses("onboarding", id, 5, null));
            }
            case "store-files" -> {
                sections.put("responses", responses("expenses", id, 3, null));
                sections.put("files", files(10));
            }
            case "choices-from-web" -> {
                sections.put("responses", responses("visit", id, 3, null));
                sections.put("offices", jdbc.sql("SELECT id, name, region FROM offices ORDER BY region, name").query().listOfRows());
                sections.put("countries cache", cache.has("countries") ? "cached for 24 hours" : "empty: the next request fetches restcountries.com");
            }
            case "async-functions" -> {
                sections.put("customers", jdbc.sql("SELECT email FROM customers").query(String.class).list());
                sections.put("shipping_rates", jdbc.sql("SELECT postcode_prefix, price FROM shipping_rates ORDER BY postcode_prefix").query().listOfRows());
            }
            case "relational-storage" -> sections.putAll(claims(id));
            case "fill-together" -> {
                sections.put("relay room", relayRoom(room, "fill"));
                sections.put("responses", responses("job-sheet", id, 3, null));
            }
            case "dashboard" -> sections.put("responses", jdbc.sql("SELECT COUNT(*) FROM responses WHERE form_id = 'feedback'").query(Integer.class).single());
            case "creator-load-save" -> sections.put("forms", document("forms", key.isEmpty() ? "support" : key));
            case "ai-translation" -> sections.put("forms", document("forms", "support-translations"));
            case "creator-uploads" -> {
                sections.put("forms", document("forms", "support-assets"));
                sections.put("files", files(5));
            }
            case "variable-presets" -> sections.put("variable_presets", document("variable_presets", key.isEmpty() ? "support" : key));
            case "edit-together" -> {
                sections.put("relay room", relayRoom(room, "edit"));
                sections.put("forms", document("forms", key.isEmpty() ? "support-team" : key));
            }
            case "validate-response" -> sections.put("responses", responses("claim", id, 5, null));
            case "lint-definition" -> sections.put("forms", document("forms", "support-lint"));
            case "server-pdf" -> sections.put("responses", responses("claim", id, 10, null));
            case "extract-from-paper" -> {
                sections.put("row counts", rowCounts());
                sections.put("responses", responses("work-order", id, 3, null));
            }
            default -> throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return sections;
    }

    /** The latest responses (or one, by id), with their data parsed. H2 can't filter inside the JSON text, so the
     *  customer's email is compared after reading each document. */
    private List<Map<String, Object>> responses(String formId, Integer id, int limit, String customerEmail) {
        StringBuilder sql = new StringBuilder("SELECT id, form_id, definition_version, data, created_at FROM responses WHERE 1 = 1");
        List<Object> params = new ArrayList<>();
        if (formId != null) {
            sql.append(" AND form_id = ?");
            params.add(formId);
        }
        if (id != null) {
            sql.append(" AND id = ?");
            params.add(id);
        }
        sql.append(" ORDER BY id DESC");
        if (customerEmail == null) {
            sql.append(" LIMIT ").append(limit);
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> row : jdbc.sql(sql.toString()).params(params).query().listOfRows()) {
            JsonNode data = json.readTree((String) row.get("data"));
            if (customerEmail != null && !customerEmail.equals(data.path("customer_email").asString(null))) {
                continue;
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("data", data);
            entry.put("id", row.get("id"));
            entry.put("form_id", row.get("form_id"));
            entry.put("definition_version", row.get("definition_version"));
            entry.put("created_at", row.get("created_at"));
            rows.add(entry);
            if (rows.size() == limit) {
                break;
            }
        }
        return rows;
    }

    private Map<String, Object> document(String table, String key) {
        return jdbc.sql("SELECT json FROM " + table + " WHERE key = ?").param(key).query(String.class).optional()
                .<Map<String, Object>>map(text -> {
                    Map<String, Object> doc = new LinkedHashMap<>();
                    doc.put("key", key);
                    doc.put("json", json.readTree(text));
                    return doc;
                })
                .orElse(null);
    }

    private Map<String, Object> claims(Integer id) {
        List<Map<String, Object>> rows = jdbc.sql("SELECT claims.id AS claim_id, claims.response_id, claims.customer_email, claims.amount,"
                        + " responses.form_id, responses.definition_version, responses.created_at, responses.data"
                        + " FROM claims JOIN responses ON responses.id = claims.response_id"
                        + (id != null ? " WHERE responses.id = ?" : "") + " ORDER BY claims.id DESC LIMIT 3")
                .params(id != null ? List.of(id) : List.of()).query().listOfRows();
        List<Map<String, Object>> claims = new ArrayList<>();
        List<Map<String, Object>> responses = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> claim = new LinkedHashMap<>();
            claim.put("claim_id", row.get("claim_id"));
            claim.put("response_id", row.get("response_id"));
            claim.put("customer_email", row.get("customer_email"));
            claim.put("amount", row.get("amount"));
            claims.add(claim);
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("id", row.get("response_id"));
            response.put("form_id", row.get("form_id"));
            response.put("definition_version", row.get("definition_version"));
            response.put("created_at", row.get("created_at"));
            response.put("data", json.readTree((String) row.get("data")));
            responses.add(response);
        }
        Map<String, Object> sections = new LinkedHashMap<>();
        sections.put("claims", claims);
        sections.put("responses", responses);
        return sections;
    }

    private List<Map<String, Object>> files(int limit) {
        return jdbc.sql("SELECT id, name, type FROM files ORDER BY _ROWID_ DESC LIMIT " + limit).query().listOfRows();
    }

    private Map<String, Object> rowCounts() {
        Map<String, Object> counts = new LinkedHashMap<>();
        for (String table : List.of("forms", "responses", "progress", "files", "claims", "variable_presets")) {
            counts.put(table, jdbc.sql("SELECT COUNT(*) FROM " + table).query(Integer.class).single());
        }
        return counts;
    }

    private Object relayRoom(String room, String relay) {
        if (!ROOM.matcher(room).matches()) {
            return null;
        }
        if (!settings.demoMode()) {
            return "The relay writes room snapshots in demo mode only (DEMO_MODE=true)";
        }
        Path path = roomKey.snapshotPath(RoomKey.of(SandboxContext.id(), room), relay);
        try {
            return path != null && Files.isRegularFile(path) ? json.readTree(Files.readString(path)) : "No room data";
        } catch (IOException e) {
            return "No room data";
        }
    }
}
