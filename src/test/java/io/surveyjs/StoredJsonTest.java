package io.surveyjs;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * JSON is stored exactly as it arrives: the contract's round-trip document (plus a 1.0) through every storing
 * endpoint, read back from H2 itself and compared as parsed JSON; and bodies that aren't JSON (NaN, trailing tokens)
 * refused with the contract's 400.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class StoredJsonTest extends TestSupport {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    private JsonNode stored(String sql, Object... params) {
        return json(jdbc.sql(sql).params(List.of(params)).query(String.class).single());
    }

    private MvcResult send(MockHttpServletRequestBuilder request, String body) throws Exception {
        return mvc.perform(request.cookie(user("alice")).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
    }

    @Test
    void saveResponseStoresTheDocumentAsSent() throws Exception {
        MvcResult r = send(post("/api/responses"), "{\"formId\":\"roundtrip\",\"data\":" + ROUNDTRIP_TEXT + "}");
        assertThat(r.getResponse().getStatus()).isEqualTo(201);
        JsonNode answer = json(r);
        assertThat(answer.propertyNames()).containsExactly("id");
        assertThat(answer.get("id").isInt()).isTrue();
        int id = answer.get("id").asInt();
        assertThat(id).isGreaterThanOrEqualTo(1);
        assertThat(stored("SELECT data FROM responses WHERE id = ?", id)).isEqualTo(roundtrip());
        String text = jdbc.sql("SELECT data FROM responses WHERE id = ?").param(id).query(String.class).single();
        assertThat(text).contains("\"n\":1.0").contains("naïve — 東京").contains("\"a\":\"  padded  \"");
        assertThat(jdbc.sql("SELECT form_id FROM responses WHERE id = ?").param(id).query(String.class).single()).isEqualTo("roundtrip");
    }

    @Test
    void createdAtIsAnIsoUtcStringToTheSecond() throws Exception {
        int id = json(send(post("/api/responses"), "{\"formId\":\"roundtrip\",\"data\":{}}")).get("id").asInt();
        String createdAt = jdbc.sql("SELECT created_at FROM responses WHERE id = ?").param(id).query(String.class).single();
        assertThat(createdAt).matches("^\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\dZ$");
        assertThat(stored("SELECT data FROM responses WHERE id = ?", id)).isEqualTo(json("{}"));
    }

    @Test
    void resumeProgressStoresDataAndUiState() throws Exception {
        String document = "{\"data\":" + ROUNDTRIP_TEXT + ",\"uiState\":{\"currentPageName\":\"page2\"}}";
        assertThat(send(put("/api/progress/roundtrip"), document).getResponse().getStatus()).isEqualTo(204);
        assertThat(stored("SELECT json FROM progress WHERE key = '1:roundtrip'")).isEqualTo(json(document));
        MvcResult loaded = mvc.perform(get("/api/progress/roundtrip").cookie(user("alice"))).andReturn();
        assertThat(json(loaded)).isEqualTo(json(document));
    }

    @Test
    void relationalStorageKeepsTheOriginalAndCopiesTheColumns() throws Exception {
        ObjectNode data = (ObjectNode) roundtrip();
        data.put("customer_email", "a@example.com");
        data.put("amount", 10.0);
        MvcResult r = send(post("/api/claims"), "{\"data\":" + data + ",\"definitionVersion\":\"v2\"}");
        assertThat(r.getResponse().getStatus()).isEqualTo(201);
        int id = json(r).get("id").asInt();
        assertThat(stored("SELECT data FROM responses WHERE id = ?", id)).isEqualTo(data);
        Map<String, Object> response = jdbc.sql("SELECT form_id, definition_version FROM responses WHERE id = ?").param(id).query().singleRow();
        assertThat(response).containsEntry("form_id", "claim").containsEntry("definition_version", "v2");
        Map<String, Object> claim = jdbc.sql("SELECT customer_email, amount FROM claims WHERE response_id = ?").param(id).query().singleRow();
        assertThat(claim).containsEntry("customer_email", "a@example.com").containsEntry("amount", 10.0);
    }

    @Test
    void creatorLoadSaveStoresTheDefinition() throws Exception {
        assertThat(send(put("/api/forms/roundtrip"), ROUNDTRIP_TEXT).getResponse().getStatus()).isEqualTo(204);
        assertThat(stored("SELECT json FROM forms WHERE key = 'roundtrip'")).isEqualTo(roundtrip());
        MvcResult loaded = mvc.perform(get("/api/forms/roundtrip").cookie(user("alice"))).andReturn();
        assertThat(json(loaded).get("definition")).isEqualTo(roundtrip());
    }

    @Test
    void variablePresetsStoreTheList() throws Exception {
        String presets = "[{\"name\":\"basic\",\"variables\":{\"extra\":{}}},{\"name\":\"premium\",\"description\":\"\",\"variables\":" + ROUNDTRIP_TEXT + "}]";
        assertThat(send(put("/api/variable-presets/roundtrip"), presets).getResponse().getStatus()).isEqualTo(204);
        assertThat(stored("SELECT json FROM variable_presets WHERE key = 'roundtrip'")).isEqualTo(json(presets));
        MvcResult loaded = mvc.perform(get("/api/variable-presets/roundtrip").cookie(user("alice"))).andReturn();
        assertThat(json(loaded)).isEqualTo(json(presets));
    }

    @Test
    void theDashboardReturnsTheStoredDocuments() throws Exception {
        send(post("/api/responses"), "{\"formId\":\"roundtrip-dashboard\",\"data\":" + ROUNDTRIP_TEXT + "}");
        MvcResult r = mvc.perform(get("/api/responses").param("formId", "roundtrip-dashboard")).andReturn();
        assertThat(r.getResponse().getContentType()).startsWith("application/json");
        assertThat(json(r)).isEqualTo(json("[" + ROUNDTRIP_TEXT + "]"));
        MvcResult dated = mvc.perform(get("/api/responses").param("formId", "roundtrip-dashboard").param("includeCreatedAt", "1")).andReturn();
        assertThat(json(dated).get(0).get("data")).isEqualTo(roundtrip());
        assertThat(json(dated).get(0).get("createdAt").asString()).matches("^\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\dZ$");
    }

    @Test
    void bodiesThatAreNotJsonAnswer400AndStoreNothing() throws Exception {
        List<String> bodies = List.of("{\"formId\":\"x\",\"data\":NaN}", "{\"formId\":\"x\",\"data\":Infinity}",
                "{\"formId\":\"x\",\"data\":{}} trailing", "{\"formId\":\"x\",\"data\":{}}}", "{\"formId\":\"x\",\"data\":{}} {}",
                "{not json", "");
        Map<String, Integer> before = counts();
        for (String body : bodies) {
            for (String[] endpoint : new String[][] {{"POST", "/api/responses"}, {"PUT", "/api/progress/not-json"}, {"POST", "/api/claims"},
                    {"PUT", "/api/forms/not-json"}, {"PUT", "/api/variable-presets/not-json"}}) {
                MockHttpServletRequestBuilder request = endpoint[0].equals("POST") ? post(endpoint[1]) : put(endpoint[1]);
                MvcResult r = send(request, body);
                assertThat(r.getResponse().getStatus()).as(endpoint[0] + " " + endpoint[1] + " with " + body).isEqualTo(400);
                assertError(r, 400, "The request body is not valid JSON");
            }
        }
        assertThat(counts()).isEqualTo(before);
    }

    private Map<String, Integer> counts() {
        Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        for (String table : TABLES) {
            counts.put(table, jdbc.sql("SELECT COUNT(*) FROM " + table).query(Integer.class).single());
        }
        return counts;
    }
}
