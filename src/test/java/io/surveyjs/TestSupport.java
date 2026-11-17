package io.surveyjs;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.servlet.http.Cookie;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the test classes share: a fresh DATA_DIR for every Spring context they start, and small helpers.
 *
 * <p>The {@link DynamicPropertySource} method runs once per context Spring creates, so each context gets its own
 * folder under {@code target/test-data} (H2 locks a database folder per process), while classes with identical
 * configuration still share one cached context.
 */
public abstract class TestSupport {

    public static final JsonMapper JSON = JsonMapper.shared();

    /** For the tests that need Tomcat itself (RemoteIpValve, the firewall's Tomcat half, multipart limits, relays). */
    public static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    /** The contract's round-trip document, plus a 1.0 that must stay 1.0. */
    public static final String ROUNDTRIP_TEXT = "{ \"a\": \"  padded  \", \"b\": \"\", \"c\": null, \"d\": {}, \"e\": [{}], \"f\": [], \"g\": 1.5,"
            + " \"h\": \"naïve — 東京\", \"i\": { \"j\": { \"k\": [1, \"x\", true] } }, \"n\": 1.0 }";

    public static final Path PNG_FILE = Path.of("shared", "samples", "work-order-scan.png");

    public static final List<String> TABLES = List.of("forms", "responses", "progress", "files", "claims", "variable_presets");

    private static final Pattern PAGE_CONFIG = Pattern.compile("<script>window\\.SURVEYJS_PAGE = (\\{.*?\\});</script>");

    @DynamicPropertySource
    static void dataDir(DynamicPropertyRegistry registry) throws IOException {
        Path dir = newDataDir();
        registry.add("surveyjs.data-dir", dir::toString);
    }

    /** A new, empty DATA_DIR under target/test-data. */
    public static Path newDataDir() throws IOException {
        Path root = Path.of("target", "test-data").toAbsolutePath();
        Files.createDirectories(root);
        return Files.createTempDirectory(root, "data-");
    }

    public static JsonNode roundtrip() {
        return JSON.readTree(ROUNDTRIP_TEXT);
    }

    public static JsonNode json(String text) {
        return JSON.readTree(text);
    }

    public static JsonNode json(MvcResult result) {
        return JSON.readTree(body(result));
    }

    public static String body(MvcResult result) {
        try {
            return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static Cookie user(String key) {
        return new Cookie("demo_user", key);
    }

    public static byte[] png() throws IOException {
        return Files.readAllBytes(PNG_FILE);
    }

    /** window.SURVEYJS_PAGE of a rendered example page. */
    public static JsonNode pageConfig(String html) {
        Matcher config = PAGE_CONFIG.matcher(html);
        assertThat(config.find()).as("window.SURVEYJS_PAGE in the page").isTrue();
        return JSON.readTree(config.group(1));
    }

    /** Asserts an answer is exactly { error } with this status and text. */
    public static void assertError(MvcResult result, int status, String message) {
        MockHttpServletResponse response = result.getResponse();
        assertThat(response.getStatus()).as("status of " + body(result)).isEqualTo(status);
        assertError(body(result), message);
        assertThat(response.getContentType()).startsWith("application/json");
    }

    /** Asserts a body is exactly { error } (one key, a string) with this text. */
    public static void assertError(String body, String message) {
        JsonNode error = JSON.readTree(body);
        assertThat(error.isObject()).as(body).isTrue();
        assertThat(error.propertyNames()).as(body).containsExactly("error");
        assertThat(error.get("error").isString()).as(body).isTrue();
        if (message != null) {
            assertThat(error.get("error").asString()).isEqualTo(message);
        }
    }

    /** A multipart/form-data body as a browser sends it (names in UTF-8): {field, file name, content type, bytes} each. */
    public static byte[] multipartBody(String boundary, Object[]... parts) throws IOException {
        java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
        for (Object[] part : parts) {
            body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + part[0] + "\"; filename=\"" + part[1] + "\"\r\n"
                    + "Content-Type: " + part[2] + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            body.write((byte[]) part[3]);
            body.write("\r\n".getBytes(StandardCharsets.UTF_8));
        }
        body.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return body.toByteArray();
    }

    /** POSTs a multipart body to a real port. */
    public static java.net.http.HttpResponse<String> postMultipart(int port, String path, String cookie, Object[]... parts) throws Exception {
        String boundary = "----surveyjs-test-" + System.nanoTime();
        var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(java.net.http.HttpRequest.BodyPublishers.ofByteArray(multipartBody(boundary, parts)));
        if (cookie != null) {
            request.header("Cookie", cookie);
        }
        return HTTP.send(request.build(), java.net.http.HttpResponse.BodyHandlers.ofString());
    }

    /** The value of a Set-Cookie line for this cookie, or null. */
    public static String setCookie(List<String> lines, String name) {
        return lines.stream().filter(line -> line.startsWith(name + "=")).findFirst().orElse(null);
    }

    public static String cookieValue(String setCookie) {
        return setCookie.substring(setCookie.indexOf('=') + 1, setCookie.indexOf(';') < 0 ? setCookie.length() : setCookie.indexOf(';'));
    }
}
