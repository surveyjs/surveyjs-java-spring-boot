package io.surveyjs;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.surveyjs.integration.SurveyJsService;
import io.surveyjs.integration.SurveyJsService.ServiceAnswer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Run Server-Side Workflows: every row of the meta prompt's status mapping, and the checks made before the service is
 * called. The service is a {@code @MockitoBean SurveyJsService}: each row is a stubbed {@link ServiceAnswer} or the
 * {@link ResponseStatusException} the real class throws. Each test asserts the status, the body, the row counts
 * (nothing saved unless a success row) and that the submitted answers never reach the log.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"surveyjs.validate-responses=true", "surveyjs.lint-definitions=true"})
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class SurveyJsMappingTest extends TestSupport {

    /** Written on the "paper form" and in the answers: must never reach the log. */
    static final String SECRET = "Answer-written-on-the-paper-form-7f3a";
    static final String SERVICE = "http://localhost:3010";
    static final byte[] PDF = "%PDF-1.7\n% a filled claim\n%%EOF\n".getBytes(StandardCharsets.ISO_8859_1);
    static final String CLAIM = "{\"customer_email\":\"valid@example.com\",\"amount\":120,\"incident\":\"theft\",\"details\":\"" + SECRET + "\"}";
    static final String DEFINITION = "{\"elements\":[{\"type\":\"text\",\"name\":\"q1\",\"title\":\"" + SECRET + "\"}]}";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @MockitoBean
    SurveyJsService surveyjs;

    CapturedOutput output;

    @BeforeEach
    void realFailureHelpers(CapturedOutput output) {
        this.output = output;
        // unexpected(…) and unexpectedExtraction(…) only log and build the 502: keep the real ones
        when(surveyjs.unexpected(anyString(), any())).thenCallRealMethod();
        when(surveyjs.unexpected(anyString(), any(), anyString())).thenCallRealMethod();
        when(surveyjs.unexpectedExtraction(any())).thenCallRealMethod();
    }

    @AfterEach
    void answersNeverReachTheLog() {
        assertThat(output.getAll()).doesNotContain(SECRET);
    }

    // ---------- helpers

    static ServiceAnswer answer(int status, String json) {
        return new ServiceAnswer(status, "application/json", json.getBytes(StandardCharsets.UTF_8));
    }

    static ServiceAnswer answer(int status, String contentType, byte[] bytes) {
        return new ServiceAnswer(status, contentType, bytes);
    }

    /** What the service answers that the mapping doesn't list for any example. */
    static List<ServiceAnswer> unexpected() {
        return List.of(answer(200, "{\"error\":\"schema is required\"}"), answer(200, "{not json"), answer(200, "text/html", "<html>".getBytes()),
                answer(500, "{\"error\":\"boom\"}"), answer(422, "{\"warnings\":[]}"), answer(400, "{\"error\":\"INVALID_JSON\"}"),
                answer(204, "application/json", new byte[0]));
    }

    static ResponseStatusException timeout() {
        return new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "SurveyJS service timed out");
    }

    static ResponseStatusException refused() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "SurveyJS service is not running at " + SERVICE);
    }

    void serviceAnswers(ServiceAnswer answer) {
        when(surveyjs.post(anyString(), any(), any(Duration.class))).thenReturn(answer);
    }

    void serviceThrows(ResponseStatusException e) {
        when(surveyjs.post(anyString(), any(), any(Duration.class))).thenThrow(e);
    }

    Map<String, Integer> rows() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String table : TABLES) {
            counts.put(table, jdbc.sql("SELECT COUNT(*) FROM " + table).query(Integer.class).single());
        }
        return counts;
    }

    String storedForm(String key) {
        return jdbc.sql("SELECT json FROM forms WHERE key = ?").param(key).query(String.class).optional().orElse(null);
    }

    // ---------- validate-response

    MvcResult postClaim(String data) throws Exception {
        return mvc.perform(post("/api/responses").contentType(MediaType.APPLICATION_JSON).content("{\"formId\":\"claim\",\"data\":" + data + "}")).andReturn();
    }

    @Test
    void validateExactlyEmptyObjectSavesAndAnswers201() throws Exception {
        serviceAnswers(answer(200, "{}"));
        Map<String, Integer> before = rows();
        MvcResult r = postClaim(CLAIM);
        assertThat(r.getResponse().getStatus()).isEqualTo(201);
        assertThat(json(r).propertyNames()).containsExactly("id");
        int id = json(r).get("id").asInt();
        assertThat(rows().get("responses")).isEqualTo(before.get("responses") + 1);
        assertThat(json(jdbc.sql("SELECT data FROM responses WHERE id = ?").param(id).query(String.class).single())).isEqualTo(json(CLAIM));
        ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
        verify(surveyjs).post(eq("/response"), body.capture(), eq(Duration.ofSeconds(10)));
        JsonNode sent = JSON.valueToTree(body.getValue());
        assertThat(sent.propertyNames()).containsExactlyInAnyOrder("schema", "response");
        assertThat(sent.get("response")).isEqualTo(json(CLAIM));
        assertThat(sent.get("schema").has("elements")).isTrue();
    }

    @Test
    void validateErrorsAnswer400WithTheErrorsAndSaveNothing() throws Exception {
        String errors = "[{\"name\":\"incident\",\"text\":\"Unknown choice\"}]";
        serviceAnswers(answer(422, "{\"errors\":" + errors + "}"));
        Map<String, Integer> before = rows();
        MvcResult r = postClaim(CLAIM);
        assertThat(r.getResponse().getStatus()).isEqualTo(400);
        assertThat(json(r)).isEqualTo(json("{\"errors\":" + errors + "}"));
        assertThat(rows()).isEqualTo(before);
    }

    @Test
    void validateABrokenStoredDefinitionAnswers502AndIsLogged() throws Exception {
        serviceAnswers(answer(422, "{\"errors\":[{\"rule\":\"x\"}],\"warnings\":[]}"));
        Map<String, Integer> before = rows();
        assertError(postClaim(CLAIM), 502, "The stored definition has errors");
        assertThat(rows()).isEqualTo(before);
        assertThat(output.getAll()).contains("unexpected answer from /response: 422");
    }

    @Test
    void validateAnythingElseAnswers502AndSavesNothing() throws Exception {
        List<ServiceAnswer> answers = new java.util.ArrayList<>(unexpected());
        answers.add(answer(200, "{\"extra\":1}"));
        answers.add(answer(200, "[]"));
        answers.add(answer(201, "{}"));
        for (ServiceAnswer answer : answers) {
            serviceAnswers(answer);
            Map<String, Integer> before = rows();
            MvcResult r = postClaim(CLAIM);
            assertThat(r.getResponse().getStatus()).as(answer.status() + " " + new String(answer.bytes())).isEqualTo(502);
            assertError(r, 502, "SurveyJS service error");
            assertThat(rows()).isEqualTo(before);
        }
        assertThat(output.getAll()).contains("unexpected answer from /response");
    }

    @Test
    void validateTimeoutAndRefused() throws Exception {
        Map<String, Integer> before = rows();
        serviceThrows(timeout());
        assertError(postClaim(CLAIM), 504, "SurveyJS service timed out");
        serviceThrows(refused());
        assertError(postClaim(CLAIM), 503, "SurveyJS service is not running at " + SERVICE);
        assertThat(rows()).isEqualTo(before);
    }

    @Test
    void validatePrechecksNeverCallTheService() throws Exception {
        serviceAnswers(answer(200, "{}"));
        Map<String, Integer> before = rows();
        for (String body : List.of("[]", "{\"formId\":1,\"data\":" + CLAIM + "}", "{\"formId\":\"claim\",\"data\":\"answers\"}",
                "{\"formId\":\"claim\"}", "{\"formId\":\"claim\",\"data\":[\"" + SECRET + "\"]}")) {
            MvcResult r = mvc.perform(post("/api/responses").contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
            assertError(r, 400, "Expected { formId, data } with an answers object");
        }
        MvcResult r = mvc.perform(post("/api/responses").contentType(MediaType.APPLICATION_JSON)
                .content("{\"formId\":\"no-such-form\",\"data\":" + CLAIM + "}")).andReturn();
        assertError(r, 404, "Unknown form");
        verify(surveyjs, never()).post(anyString(), any(), any(Duration.class));
        assertThat(rows()).isEqualTo(before);
    }

    // ---------- lint-definition

    MvcResult putForm(String key, String definition) throws Exception {
        return mvc.perform(put("/api/forms/" + key).cookie(user("alice")).contentType(MediaType.APPLICATION_JSON).content(definition)).andReturn();
    }

    @Test
    void lintCleanSavesAndAnswers204() throws Exception {
        serviceAnswers(answer(200, "{}"));
        MvcResult r = putForm("lint-clean", DEFINITION);
        assertThat(r.getResponse().getStatus()).isEqualTo(204);
        assertThat(r.getResponse().getContentLength()).isZero();
        assertThat(json(storedForm("lint-clean"))).isEqualTo(json(DEFINITION));
        ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
        verify(surveyjs).post(eq("/schema"), body.capture(), eq(Duration.ofSeconds(10)));
        assertThat((JsonNode) JSON.valueToTree(body.getValue())).isEqualTo(json(DEFINITION));
    }

    @Test
    void lintWarningsOnlySaveAndAnswer200WithTheWarnings() throws Exception {
        String warnings = "{\"warnings\":[{\"rule\":\"property/unknown\",\"message\":\"someproperty\"}]}";
        serviceAnswers(answer(200, warnings));
        MvcResult r = putForm("lint-warned", DEFINITION);
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(r)).isEqualTo(json(warnings));
        assertThat(json(storedForm("lint-warned"))).isEqualTo(json(DEFINITION));
    }

    @Test
    void lintErrorsAnswer422AndKeepThePreviousVersion() throws Exception {
        serviceAnswers(answer(200, "{}"));
        putForm("lint-kept", "{\"title\":\"previous\"}");
        String findings = "{\"errors\":[{\"rule\":\"expression/unknown-variable\"}],\"warnings\":[]}";
        serviceAnswers(answer(422, findings));
        Map<String, Integer> before = rows();
        MvcResult r = putForm("lint-kept", DEFINITION);
        assertThat(r.getResponse().getStatus()).isEqualTo(422);
        assertThat(json(r)).isEqualTo(json(findings));
        assertThat(json(storedForm("lint-kept"))).isEqualTo(json("{\"title\":\"previous\"}"));
        assertThat(rows()).isEqualTo(before);
    }

    @Test
    void lintAnythingElseAnswers502AndSavesNothing() throws Exception {
        List<ServiceAnswer> answers = new java.util.ArrayList<>(unexpected());
        answers.add(answer(422, "{\"errors\":[]}"));
        answers.add(answer(400, "{\"error\":\"INVALID_SCHEMA\"}"));
        answers.add(answer(200, "{\"warnings\":[],\"extra\":1}"));
        for (ServiceAnswer answer : answers) {
            serviceAnswers(answer);
            Map<String, Integer> before = rows();
            MvcResult r = putForm("lint-refused", DEFINITION);
            assertThat(r.getResponse().getStatus()).as(answer.status() + " " + new String(answer.bytes())).isEqualTo(502);
            assertError(r, 502, "SurveyJS service error");
            assertThat(storedForm("lint-refused")).isNull();
            assertThat(rows()).isEqualTo(before);
        }
        assertThat(output.getAll()).contains("unexpected answer from /schema");
    }

    @Test
    void lintTimeoutAndRefused() throws Exception {
        serviceThrows(timeout());
        assertError(putForm("lint-failed", DEFINITION), 504, "SurveyJS service timed out");
        serviceThrows(refused());
        assertError(putForm("lint-failed", DEFINITION), 503, "SurveyJS service is not running at " + SERVICE);
        assertThat(storedForm("lint-failed")).isNull();
    }

    @Test
    void lintChecksTheEditFormsPermissionBeforeTheService() throws Exception {
        serviceAnswers(answer(200, "{}"));
        for (String who : new String[] {"bob", null}) {
            var request = put("/api/forms/lint-bob").contentType(MediaType.APPLICATION_JSON).content(DEFINITION);
            MvcResult r = mvc.perform(who == null ? request : request.cookie(user(who))).andReturn();
            assertError(r, 403, "You don't have editor rights: only editors can change forms");
        }
        verify(surveyjs, never()).post(anyString(), any(), any(Duration.class));
        assertThat(storedForm("lint-bob")).isNull();
    }

    // ---------- server-pdf and the blank work order

    int claimWithVersion(String version) {
        return jdbc.sql("SELECT id FROM responses WHERE form_id = 'claim' AND definition_version = ? ORDER BY id LIMIT 1")
                .param(version).query(Integer.class).single();
    }

    @Test
    void pdfBytesPassThroughWithTheContractHeaders() throws Exception {
        serviceAnswers(answer(200, "application/pdf", PDF));
        int claim = claimWithVersion("v1");
        Map<String, Integer> before = rows();
        MvcResult r = mvc.perform(get("/api/claims/" + claim + "/pdf")).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(r.getResponse().getContentAsByteArray()).isEqualTo(PDF);
        assertThat(r.getResponse().getContentType()).isEqualTo("application/pdf");
        assertThat(r.getResponse().getHeader("Content-Disposition")).isEqualTo("inline; filename=\"claim-" + claim + ".pdf\"");
        assertThat(directives(r.getResponse().getHeader("Cache-Control"))).containsExactly("no-store", "private");
        ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
        verify(surveyjs).post(eq("/pdf"), body.capture(), eq(Duration.ofSeconds(60)));
        JsonNode sent = JSON.valueToTree(body.getValue());
        assertThat(sent.propertyNames()).containsExactlyInAnyOrder("schema", "response");
        assertThat(sent.get("response")).isEqualTo(json(jdbc.sql("SELECT data FROM responses WHERE id = ?").param(claim).query(String.class).single()));

        r = mvc.perform(get("/api/work-orders/blank.pdf")).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(r.getResponse().getContentAsByteArray()).isEqualTo(PDF);
        assertThat(r.getResponse().getContentType()).isEqualTo("application/pdf");
        assertThat(r.getResponse().getHeader("Content-Disposition")).isEqualTo("attachment; filename=\"work-order.pdf\"");
        assertThat(directives(r.getResponse().getHeader("Cache-Control"))).containsExactly("no-store", "private");
        verify(surveyjs, org.mockito.Mockito.times(2)).post(eq("/pdf"), body.capture(), eq(Duration.ofSeconds(60)));
        assertThat(JSON.<JsonNode>valueToTree(body.getValue()).propertyNames()).containsExactly("schema");
        assertThat(rows()).isEqualTo(before);
    }

    @Test
    void pdfOfEveryRecordedVersionUsesItsDefinition() throws Exception {
        serviceAnswers(answer(200, "application/pdf", PDF));
        for (String version : new String[] {"v1", "v2"}) {
            int claim = claimWithVersion(version);
            assertThat(mvc.perform(get("/api/claims/" + claim + "/pdf")).andReturn().getResponse().getStatus()).isEqualTo(200);
        }
        int unversioned = jdbc.sql("SELECT id FROM responses WHERE form_id = 'claim' AND definition_version IS NULL ORDER BY id LIMIT 1")
                .query(Integer.class).single();
        assertThat(mvc.perform(get("/api/claims/" + unversioned + "/pdf")).andReturn().getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void pdfAnythingElseAnswers502() throws Exception {
        List<ServiceAnswer> answers = new java.util.ArrayList<>(unexpected());
        answers.add(answer(200, "application/pdf", "not a pdf".getBytes()));
        answers.add(answer(200, "application/octet-stream", PDF));
        answers.add(answer(200, null, PDF));
        answers.add(answer(201, "application/pdf", PDF));
        answers.add(answer(422, "{\"errors\":[],\"warnings\":[]}"));
        for (ServiceAnswer answer : answers) {
            serviceAnswers(answer);
            for (String path : List.of("/api/claims/" + claimWithVersion("v1") + "/pdf", "/api/work-orders/blank.pdf")) {
                MvcResult r = mvc.perform(get(path)).andReturn();
                assertThat(r.getResponse().getStatus()).as(path + " " + answer.status() + " " + answer.contentType()).isEqualTo(502);
                assertError(r, 502, "SurveyJS service error");
            }
        }
        assertThat(output.getAll()).contains("unexpected answer from /pdf");
    }

    @Test
    void pdfTimeoutAndRefused() throws Exception {
        for (String path : List.of("/api/claims/" + claimWithVersion("v1") + "/pdf", "/api/work-orders/blank.pdf")) {
            serviceThrows(timeout());
            assertError(mvc.perform(get(path)).andReturn(), 504, "SurveyJS service timed out");
            serviceThrows(refused());
            assertError(mvc.perform(get(path)).andReturn(), 503, "SurveyJS service is not running at " + SERVICE);
        }
    }

    @Test
    void pdfPrechecksNeverCallTheService() throws Exception {
        serviceAnswers(answer(200, "application/pdf", PDF));
        assertError(mvc.perform(get("/api/claims/999999/pdf")).andReturn(), 404, "Unknown claim");
        assertError(mvc.perform(get("/api/claims/abc/pdf")).andReturn(), 404, "Not found");
        int feedback = jdbc.sql("SELECT id FROM responses WHERE form_id = 'feedback' ORDER BY id LIMIT 1").query(Integer.class).single();
        assertError(mvc.perform(get("/api/claims/" + feedback + "/pdf")).andReturn(), 404, "Unknown claim");   // not a claim
        jdbc.sql("INSERT INTO responses (form_id, definition_version, data, created_at) VALUES ('claim', 'v9', ?, '2026-11-16T09:00:00Z')")
                .param("{\"details\":\"" + SECRET + "\"}").update();
        int v9 = claimWithVersion("v9");
        assertError(mvc.perform(get("/api/claims/" + v9 + "/pdf")).andReturn(), 409, "The schema version for this claim is unavailable");
        verify(surveyjs, never()).post(anyString(), any(), any(Duration.class));
    }

    @Test
    void theBlankWorkOrderOfAnUnknownFormAnswers404() throws Exception {
        serviceAnswers(answer(200, "application/pdf", PDF));
        String workOrder = storedForm("work-order");
        jdbc.sql("DELETE FROM forms WHERE key = 'work-order'").update();
        try {
            assertError(mvc.perform(get("/api/work-orders/blank.pdf")).andReturn(), 404, "Unknown work order form");
            verify(surveyjs, never()).post(anyString(), any(), any(Duration.class));
        } finally {
            jdbc.sql("INSERT INTO forms (key, json) VALUES ('work-order', ?)").param(workOrder).update();
        }
    }

    // ---------- extract-from-paper

    static final String EXTRACTED = "{\"data\":{\"customer\":\"" + SECRET + "\",\"hours\":null},\"confidence\":[{\"fieldName\":\"customer\",\"value\":\""
            + SECRET + "\",\"confidence\":0.42,\"flagged\":true}],\"uniqueId\":\"WO-1001\"}";

    MvcResult extract(byte[] content, String field) throws Exception {
        return mvc.perform(multipart("/api/work-orders/extract").file(new MockMultipartFile(field, "scan.png", "image/png", content))).andReturn();
    }

    @Test
    void extractAnswersTheDraftAndSavesNothing() throws Exception {
        serviceAnswers(answer(200, EXTRACTED));
        Map<String, Integer> before = rows();
        MvcResult r = extract(png(), "scan");
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        JsonNode extracted = json(EXTRACTED);
        assertThat(json(r)).isEqualTo(json("{\"answers\":" + extracted.get("data") + ",\"confidence\":" + extracted.get("confidence")
                + ",\"uniqueId\":\"WO-1001\"}"));
        assertThat(rows()).isEqualTo(before);
        ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
        verify(surveyjs).post(eq("/extract"), body.capture(), eq(Duration.ofSeconds(120)));
        JsonNode sent = JSON.valueToTree(body.getValue());   // JSON with base64, never multipart
        assertThat(sent.propertyNames()).containsExactlyInAnyOrder("schema", "document");
        assertThat(sent.get("document").isString()).isTrue();
        assertThat(sent.get("document").asString()).startsWith("iVBOR");
        assertThat(java.util.Base64.getDecoder().decode(sent.get("document").asString())).isEqualTo(png());
        assertThat(sent.get("schema")).isEqualTo(json(storedForm("work-order")));
    }

    @Test
    void extractWithoutConfidenceOrIdAnswersEmptyAndNull() throws Exception {
        serviceAnswers(answer(200, "{\"data\":{\"customer\":\"" + SECRET + "\"}}"));
        MvcResult r = extract(png(), "scan");
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(r)).isEqualTo(json("{\"answers\":{\"customer\":\"" + SECRET + "\"},\"confidence\":[],\"uniqueId\":null}"));
    }

    @Test
    void extractRecognizedFailures() throws Exception {
        Object[][] rows = {
            {400, "INVALID_DOCUMENT", 400, "The file is not a PDF, PNG, JPEG, WebP or GIF document"},
            {503, "AI_NOT_CONFIGURED", 503, "The SurveyJS service has no AI provider configured"},
            {502, "EXTRACTION_FAILED", 502, "Could not extract the response from the document"},
        };
        for (Object[] row : rows) {
            serviceAnswers(answer((int) row[0], "{\"error\":\"" + row[1] + "\"}"));
            Map<String, Integer> before = rows();
            assertError(extract(png(), "scan"), (int) row[2], (String) row[3]);
            assertThat(rows()).isEqualTo(before);
        }
    }

    @Test
    void extractAnythingElseAnswers502LoggedByStatusOnly() throws Exception {
        List<ServiceAnswer> answers = new java.util.ArrayList<>(unexpected());
        answers.add(answer(200, "{\"error\":\"" + SECRET + " is required\"}"));
        answers.add(answer(200, "{\"data\":[\"" + SECRET + "\"]}"));
        answers.add(answer(200, "{\"data\":\"" + SECRET + "\"}"));
        answers.add(answer(200, "{\"confidence\":[]}"));
        answers.add(answer(201, EXTRACTED));
        answers.add(answer(422, "{\"errors\":[\"" + SECRET + "\"],\"warnings\":[]}"));
        answers.add(answer(400, "{\"error\":\"EXTRACTION_FAILED\"}"));
        answers.add(answer(502, "{\"error\":\"INVALID_DOCUMENT\"}"));
        answers.add(answer(500, "text/plain", ("upstream said: " + SECRET).getBytes()));
        for (ServiceAnswer answer : answers) {
            serviceAnswers(answer);
            Map<String, Integer> before = rows();
            MvcResult r = extract(png(), "scan");
            assertThat(r.getResponse().getStatus()).as(answer.status() + " " + new String(answer.bytes())).isEqualTo(502);
            assertError(r, 502, "SurveyJS service error");
            assertThat(rows()).isEqualTo(before);
        }
        assertThat(output.getAll()).contains("unexpected answer from /extract: 200").contains("unexpected answer from /extract: 422");
    }

    @Test
    void extractTimeoutAndRefused() throws Exception {
        serviceThrows(timeout());
        assertError(extract(png(), "scan"), 504, "SurveyJS service timed out");
        serviceThrows(refused());
        assertError(extract(png(), "scan"), 503, "SurveyJS service is not running at " + SERVICE);
    }

    @Test
    void extractPrechecksNeverCallTheService() throws Exception {
        serviceAnswers(answer(200, EXTRACTED));
        Map<String, Integer> before = rows();
        assertError(extract(png(), "document"), 400, "Upload the scan in a field named \"scan\"");
        assertError(mvc.perform(multipart("/api/work-orders/extract").param("scan", "not a file")).andReturn(), 413,
                "The scan must be a file of 5 MB or less");
        byte[] big = Arrays.copyOf(png(), 5 * 1024 * 1024 + 1);
        assertError(extract(big, "scan"), 413, "The scan must be a file of 5 MB or less");
        assertError(extract(("Not a scan: " + SECRET + " in a file named .png\n").getBytes(), "scan"), 400,
                "The file is not a PDF, PNG, JPEG, WebP or GIF document");
        verify(surveyjs, never()).post(anyString(), any(), any(Duration.class));
        assertThat(rows()).isEqualTo(before);
        byte[] exactly = Arrays.copyOf(png(), 5 * 1024 * 1024);   // 5 MB is allowed
        assertThat(extract(exactly, "scan").getResponse().getStatus()).isEqualTo(200);
    }

    static List<String> directives(String cacheControl) {
        return Arrays.stream(cacheControl.split(",")).map(String::strip).sorted().toList();
    }
}
