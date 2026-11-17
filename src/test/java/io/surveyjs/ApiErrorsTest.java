package io.surveyjs;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import io.surveyjs.integration.AiTranslation;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Under /api every error is exactly { error } (one key, a string) with its status, including Spring's own answers
 * ({timestamp, status, error, path} otherwise) and Tomcat's; outside /api an error is a small HTML page.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "surveyjs.ai-api-key=test-key")
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class ApiErrorsTest extends TestSupport {

    private static final long LIMIT = 24L * 1024 * 1024;

    @Autowired
    MockMvc mvc;

    @LocalServerPort
    int port;

    @MockitoBean
    AiTranslation ai;

    private MvcResult perform(org.springframework.test.web.servlet.RequestBuilder request) throws Exception {
        return mvc.perform(request).andReturn();
    }

    @Test
    void badRequests() throws Exception {
        assertError(perform(get("/api/responses").param("formId", "feedback").param("limit", "abc")), 400, "Invalid limit: abc");
        assertError(perform(post("/api/responses").contentType(MediaType.APPLICATION_JSON).content("{not json")), 400,
                "The request body is not valid JSON");
        assertError(perform(post("/api/responses").contentType(MediaType.APPLICATION_JSON).content("{\"formId\":\"contact\"}")), 400,
                "Expected { formId, data }");
        assertError(perform(post("/api/claims").contentType(MediaType.APPLICATION_JSON).content("{\"data\":{}}")), 400,
                "Expected { data, definitionVersion }");
    }

    @Test
    void unauthorizedAndForbidden() throws Exception {
        assertError(perform(get("/api/progress/onboarding")), 401, "Sign in to load progress");
        assertError(perform(put("/api/forms/support").cookie(user("bob")).contentType(MediaType.APPLICATION_JSON).content("{}")), 403,
                "You don't have editor rights: only editors can change forms");
    }

    @Test
    void notFound() throws Exception {
        assertError(perform(get("/api/no-such-thing")), 404, "Not found");
        assertError(perform(get("/api/forms/no-such-form")), 404, "Unknown form");
        assertError(perform(get("/api/claims/abc/pdf")), 404, "Not found");   // {id:\d+}: not a 400 type mismatch
    }

    @Test
    void methodNotAllowedListsEveryMethodOfThePath() throws Exception {
        MvcResult r = perform(put("/api/customers/exists"));
        assertError(r, 405, "Method not allowed");
        assertThat(r.getResponse().getHeader("Allow")).isEqualTo("GET");

        r = perform(post("/api/progress/x").cookie(user("alice")).contentType(MediaType.APPLICATION_JSON).content("{}"));
        assertError(r, 405, "Method not allowed");
        assertThat(Arrays.stream(r.getResponse().getHeader("Allow").split(",")).map(String::strip).toList())
                .containsExactlyInAnyOrder("GET", "PUT", "DELETE");
    }

    @Test
    void aJsonBodyOverTheLimitAnswers413BeforeAnythingReadsIt() throws Exception {
        byte[] body = new byte[(int) LIMIT + 1];
        Arrays.fill(body, (byte) ' ');
        assertError(perform(post("/api/responses").contentType(MediaType.APPLICATION_JSON).content(body)), 413, "The request body is too large");
    }

    @Test
    void aChunkedJsonBodyOverTheLimitAnswers413() throws Exception {
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/responses"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new Filler(new byte[0], LIMIT + 1024 * 1024, new byte[0]))).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).isEqualTo(413);
        assertError(r.body(), "The request body is too large");
    }

    @Test
    void anUploadOverTheMultipartLimitAnswers413() throws Exception {
        String boundary = "----surveyjs-test-boundary";
        byte[] head = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"files[]\"; filename=\"big.png\"\r\n"
                + "Content-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] tail = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        // Chunked, so no Content-Length: BodyLimitFilter lets it through, and the multipart limit (24 MB) refuses it
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/files"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary).header("Cookie", "demo_user=alice")
                .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new Filler(head, LIMIT + 512 * 1024, tail))).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).as(r.body()).isEqualTo(413);
        assertError(r.body(), "The request body is too large");
    }

    @Test
    void aFormPostToAJsonEndpointAnswers415() throws Exception {
        assertError(perform(post("/api/responses").contentType(MediaType.APPLICATION_FORM_URLENCODED).content("formId=contact&data=x")), 415,
                "Send the body as application/json");
        assertError(perform(post("/api/responses").contentType(MediaType.TEXT_PLAIN).content("{\"formId\":\"contact\",\"data\":{}}")), 415,
                "Send the body as application/json");
    }

    @Test
    void storeFilesWithoutFilesAnswers422() throws Exception {
        MvcResult r = perform(multipart("/api/files").file(new MockMultipartFile("other", "a.png", "image/png", png())).cookie(user("alice")));
        assertError(r, 422, "Upload one or more files in a field named \"files[]\"");
    }

    @Test
    void anUnexpectedExceptionIsLoggedAndAnswers500WithoutDetails(CapturedOutput output) throws Exception {
        when(ai.translate(anyList(), any(), any())).thenThrow(new RuntimeException("the database is on fire"));
        MvcResult r = perform(post("/api/translate").contentType(MediaType.APPLICATION_JSON).content("{\"strings\":[\"Hello\"],\"from\":\"en\",\"to\":\"de\"}"));
        assertError(r, 500, "Internal server error");
        assertThat(output.getAll()).contains("Unhandled error on POST /api/translate").contains("the database is on fire");
        assertThat(r.getResponse().getContentAsString()).doesNotContain("fire");
    }

    @Test
    void whatNeverReachesAHandlerIsStillErrorShapedUnderApi() throws Exception {
        // The firewall rejects an encoded slash; Tomcat passes it through (TomcatConfig), and ErrorPages answers
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/forms/..%2fsupport")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).isEqualTo(404);
        assertThat(r.headers().firstValue("Content-Type").orElse("")).startsWith("application/json");
        assertError(r.body(), "Not found");
    }

    @Test
    void outsideApiAnErrorIsHtml() throws Exception {
        Cookie alice = user("alice");
        for (String path : List.of("/examples/no-such-example", "/no-such-page")) {
            MvcResult r = perform(get(path).cookie(alice));
            assertThat(r.getResponse().getStatus()).as(path).isEqualTo(404);
            assertThat(r.getResponse().getContentType()).as(path).startsWith("text/html");
            assertThat(body(r)).as(path).startsWith("<!DOCTYPE html>");
        }
        MvcResult r = perform(get("/examples/edit-together").param("form", "bad id").cookie(alice));
        assertThat(r.getResponse().getStatus()).isEqualTo(400);
        assertThat(r.getResponse().getContentType()).startsWith("text/html");
        assertThat(body(r)).contains("Invalid form ID");

        HttpResponse<String> tomcat = HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/shared/..%2f.env")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(tomcat.statusCode()).isEqualTo(404);
        assertThat(tomcat.headers().firstValue("Content-Type").orElse("")).startsWith("text/html");
    }

    /** Demo mode: the hosted demo's 20 AI calls per visitor per day (the limit runs before the handler). */
    @Nested
    @TestPropertySource(properties = "surveyjs.demo-mode=true")
    class DemoMode {

        @Autowired
        MockMvc mvc;

        @Test
        void theTwentyFirstAiCallOfTheDayAnswers429() throws Exception {
            MvcResult first = mvc.perform(get("/api/offices")).andReturn();   // takes a demo_sid from the server
            Cookie sid = new Cookie("demo_sid", cookieValue(setCookie(first.getResponse().getHeaders("Set-Cookie"), "demo_sid")));
            String translate = "{\"strings\":[],\"from\":\"en\",\"to\":\"de\"}";
            for (int i = 0; i < 20; i++) {   // the mocked provider answers null: the controller's 502
                MvcResult r = mvc.perform(post("/api/translate").cookie(sid).contentType(MediaType.APPLICATION_JSON).content(translate)).andReturn();
                assertError(r, 502, "The AI provider returned an unexpected answer");
            }
            MvcResult limited = mvc.perform(post("/api/translate").cookie(sid).contentType(MediaType.APPLICATION_JSON).content(translate)).andReturn();
            assertError(limited, 429, "The demo allows 20 AI calls per visitor per day");
        }
    }

    /** A request body of a fixed length, produced on the fly: head, then zeros, then tail. */
    static final class Filler extends InputStream {

        private final byte[] head;
        private final byte[] tail;
        private final long fill;
        private long position;

        Filler(byte[] head, long fill, byte[] tail) {
            this.head = head;
            this.fill = fill;
            this.tail = tail;
        }

        private long length() {
            return head.length + fill + tail.length;
        }

        @Override
        public int read() {
            if (position >= length()) {
                return -1;
            }
            long at = position++;
            if (at < head.length) {
                return head[(int) at] & 0xff;
            }
            if (at < head.length + fill) {
                return ' ';
            }
            return tail[(int) (at - head.length - fill)] & 0xff;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (position >= length()) {
                return -1;
            }
            int n = 0;
            while (n < length && position < length()) {
                if (position >= head.length && position < head.length + fill) {
                    int run = (int) Math.min(length - n, head.length + fill - position);
                    Arrays.fill(buffer, offset + n, offset + n + run, (byte) ' ');
                    n += run;
                    position += run;
                } else {
                    buffer[offset + n++] = (byte) read();
                }
            }
            return n;
        }
    }
}
