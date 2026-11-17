package io.surveyjs.examples;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Analyze Results with Dashboard: GET /api/responses, the stored documents of one form, filtered and paged.
 */
@RestController
public class DashboardController {

    /** A row as stored: the answers' JSON text and when they arrived. */
    public record StoredResponse(String data, String createdAt) {
    }

    /** With includeCreatedAt: the answers and the submission date, which the Dashboard's date filter reads. */
    public record DatedResponse(JsonNode data, String createdAt) {
    }

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public DashboardController(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    // #region sjs:dashboard.server
    // GET /api/responses?formId=&includeCreatedAt=&from=&to=&limit=&offset=
    @GetMapping("/api/responses")
    public ResponseEntity<?> responses(@RequestParam(defaultValue = "") String formId,
                                       @RequestParam(defaultValue = "false") boolean includeCreatedAt,
                                       @RequestParam(defaultValue = "1970-01-01") String from, @RequestParam(defaultValue = "") String to,
                                       @RequestParam(defaultValue = "500") int limit, @RequestParam(defaultValue = "0") int offset) {
        // ISO 8601 UTC strings compare as dates; `to` includes that whole day
        List<StoredResponse> rows = jdbc.sql("SELECT data, created_at FROM responses WHERE form_id = ? AND created_at >= ?"
                        + " AND (? = '' OR SUBSTRING(created_at, 1, 10) <= ?) ORDER BY created_at, id LIMIT ? OFFSET ?")
                .params(formId, from, to, to, Math.clamp(limit, 1, 5000), Math.max(offset, 0))   // page on the server for large volumes
                .query(StoredResponse.class).list();

        // Include submission dates separately when requested by Dashboard
        if (includeCreatedAt) {
            return ResponseEntity.ok(rows.stream().map(row -> new DatedResponse(json.readTree(row.data()), row.createdAt())).toList());
        }
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                .body("[" + rows.stream().map(StoredResponse::data).collect(Collectors.joining(",")) + "]");   // as stored
    }
    // #endregion
}
