package io.surveyjs.integration;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Set;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Stores SurveyJS JSON (responses, definitions, saved progress, variable presets) exactly as it arrived.
 *
 * <p>A response or a definition is one JSON document: store it as text, never as columns. Used by save-response,
 * resume-progress, relational-storage, creator-load-save, variable-presets, validate-response, lint-definition and the
 * fill-together relay. When you copy one of them, copy this class too.
 *
 * <p>Why {@code @RequestBody JsonNode} and not a DTO: binding to a record or POJO drops the keys it doesn't declare
 * and coerces values. A {@link JsonNode} keeps every key, {@code {}}, {@code [{}]}, {@code null}, {@code ""} and
 * {@code 1.0} as they were sent, and Jackson writes it back the same way (non-ASCII as is).
 */
@Component
public class StoredJson {

    private static final Set<String> DOCUMENT_TABLES = Set.of("forms", "progress", "variable_presets");

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public StoredJson(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** JSON text for storage: the auto-configured mapper writes 1.0 as 1.0 and "naïve — 東京" as is. */
    public String write(JsonNode document) {
        return json.writeValueAsString(document);
    }

    /**
     * ISO 8601 UTC to the second, 2026-11-17T13:36:06Z: date filters are string comparisons.
     * {@code LocalDateTime} has no zone, and {@code OffsetDateTime.toString()} writes the local offset and nanoseconds.
     */
    public static String timestamp(Instant moment) {
        return moment.truncatedTo(ChronoUnit.SECONDS).toString();
    }

    public static String timestamp() {
        return timestamp(Instant.now());
    }

    /** Inserts one response, its answers as one JSON document, and returns its id. Each handler checks its own input. */
    public int insertResponse(String formId, JsonNode data) {
        return insertResponse(formId, data, null);
    }

    public int insertResponse(String formId, JsonNode data, String definitionVersion) {
        KeyHolder id = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO responses (form_id, definition_version, data, created_at) VALUES (?, ?, ?, ?)")
                .params(formId, definitionVersion, write(data), timestamp())
                .update(id, "id");
        return Objects.requireNonNull(id.getKey()).intValue();
    }

    /** Inserts or replaces the document stored under a key. The table is always one of forms, progress, variable_presets. */
    public void put(String table, String key, JsonNode document) {
        if (!DOCUMENT_TABLES.contains(table)) {
            throw new IllegalArgumentException("Not a document table: " + table);
        }
        jdbc.sql("MERGE INTO " + table + " (key, json) KEY (key) VALUES (?, ?)").params(key, write(document)).update();
    }

    /** The text of a JSON value, for a text column: a string as is, anything else as JSON, null as null. */
    public static String text(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) {
            return null;
        }
        return value.isString() ? value.asString() : value.toString();
    }
}
