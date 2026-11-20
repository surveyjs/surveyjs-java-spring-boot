package io.surveyjs;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.servlet.ServletContext;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * The pages: every example renders with its module, the import map and window.SURVEYJS_PAGE; Start over, the layout's
 * ids and the about block are where the reference has them; inline JSON can't end its script; /shared serves only its
 * four folders with the contract's types; raw traversal paths are 404; behind a TLS proxy the URLs are https and wss.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class PagesTest extends TestSupport {

    static final JsonNode MANIFEST = json(read("surveyjs-integration.json"));
    static final List<String> START_OVER = List.of("save-response", "load-definition-variables-data", "resume-progress", "store-files",
            "choices-from-web", "async-functions", "relational-storage", "validate-response", "extract-from-paper");
    static final List<String> PANEL_IDS = List.of("side-panel", "panel-toggle", "main-tabs", "panel-demo", "panel-code", "request-row",
            "stored-section", "note", "page-header", "panel-close", "panel-tabs", "hood", "panel-description", "try", "panel-requests",
            "readouts", "notes", "show-demo-requests", "clear-requests", "requests-empty", "requests", "panel-stored", "stored-updated",
            "stored", "panel-dot", "page-tabs", "component", "requests-count", "stored-dot");
    static final List<String> SPECIFIERS = List.of("survey-core", "survey-core/collaboration", "survey-core/i18n", "survey-js-ui",
            "survey-creator-core", "survey-creator-core/collaboration", "survey-creator-js", "survey-analytics", "chart.js", "@kurkle/color",
            "lodash/debounce", "highlight.js/lib/core");
    static final String ABOUT = "<div class=\"space-y-2 text-sm text-gray-600\">";

    @Autowired
    MockMvc mvc;

    @LocalServerPort
    int port;

    @Autowired
    ServletContext servletContext;

    static String read(String path) {
        try {
            return Files.readString(Path.of(path));
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static List<JsonNode> steps() {
        List<JsonNode> steps = new ArrayList<>();
        MANIFEST.get("steps").forEach(steps::add);
        return steps;
    }

    String html(String path) throws Exception {
        MvcResult r = mvc.perform(get(path).cookie(user("alice"))).andReturn();
        assertThat(r.getResponse().getStatus()).as(path).isEqualTo(200);
        assertThat(r.getResponse().getContentType()).as(path).startsWith("text/html");
        return body(r);
    }

    static JsonNode importMap(String html) {
        Matcher map = Pattern.compile("<script type=\"importmap\">(.*?)</script>").matcher(html);
        assertThat(map.find()).isTrue();
        return json(map.group(1));
    }

    @Test
    void everyExampleAndClaimRenderWithTheirModuleTheImportMapAndThePageConfig() throws Exception {
        assertThat(steps()).hasSize(18);
        String version = MANIFEST.get("surveyjsVersion").asString();
        List<String[]> pages = new ArrayList<>();
        for (JsonNode step : steps()) {
            pages.add(new String[] {"/examples/" + step.get("slug").asString(), step.get("slug").asString()});
        }
        pages.add(new String[] {"/claim", "load-definition-variables-data"});
        for (String[] page : pages) {
            JsonNode step = steps().stream().filter(s -> s.get("slug").asString().equals(page[1])).findFirst().orElseThrow();
            String html = html(page[0]);
            assertThat(html).as(page[0]).contains("window.SURVEYJS_PAGE");
            assertThat(html).as(page[0]).contains("<script type=\"module\" src=\"/shared/client/" + page[1] + ".js\"></script>");
            assertThat("/" + step.get("files").get("client").asString()).isEqualTo("/shared/client/" + page[1] + ".js");
            List<String> modules = new ArrayList<>();
            Matcher module = Pattern.compile("<script type=\"module\" src=\"([^\"]+)\">").matcher(html);
            while (module.find()) {
                modules.add(module.group(1));
            }
            assertThat(modules).as(page[0]).containsExactly("/shared/ui/code-highlight.js", "/shared/ui/page-tabs.js", "/shared/client/" + page[1] + ".js");
            assertThat(html.indexOf("<script type=\"importmap\">")).as(page[0]).isLessThan(html.indexOf("<script type=\"module\""));
            JsonNode imports = importMap(html).get("imports");
            for (String specifier : SPECIFIERS) {
                assertThat(imports.has(specifier)).as(page[0] + ": " + specifier).isTrue();
            }
            assertThat(imports.get("survey-core").asString()).isEqualTo("https://unpkg.com/survey-core@" + version + "/fesm/survey-core.mjs");
            JsonNode config = pageConfig(html);
            assertThat(config.get("slug").asString()).isEqualTo(page[1]);
            assertThat(config.get("version").asString()).isEqualTo(version);
            assertThat(config.get("relay").propertyNames()).containsExactly("fill", "edit");
            step.get("files").propertyNames().stream().filter(role -> role.startsWith("definition"))
                    .forEach(role -> assertThat(config.has(role)).as(page[0] + ": " + role).isTrue());
            assertThat(html).as(page[0]).contains("<title>" + step.get("title").asString() + " · SurveyJS + Spring Boot</title>");
            assertThat(html).as(page[0]).contains("href=\"https://surveyjs.io/documentation/backend-integration-java-spring-boot#" + step.get("anchor").asString() + "\"");
            assertThat(count(html, "<details data-code-section open")).as(page[0] + ": one code section per region").isEqualTo(step.get("regions").size());
        }
    }

    static int count(String text, String part) {
        int count = 0;
        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + 1)) {
            count++;
        }
        return count;
    }

    @Test
    void startOverIsOnExactlyTheNinePagesBeforeTheComponent() throws Exception {
        for (JsonNode step : steps()) {
            String slug = step.get("slug").asString();
            String html = html("/examples/" + slug);
            assertThat(html.contains("id=\"start-over\"")).as(slug).isEqualTo(START_OVER.contains(slug));
            if (START_OVER.contains(slug)) {
                assertThat(count(html, "id=\"start-over\"")).as(slug).isEqualTo(1);
                assertThat(html.indexOf("id=\"start-over\"")).as(slug).isLessThan(html.indexOf("id=\"component\""));
            }
        }
        assertThat(html("/claim")).contains("id=\"start-over\"");
        assertThat(html("/examples/resume-progress")).containsPattern("<button id=\"start-over\"[^>]*\sdisabled(=\"disabled\")?>");   // until the draft loads
        assertThat(html("/examples/save-response")).doesNotContainPattern("<button id=\"start-over\"[^>]*\sdisabled(=\"disabled\")?>");
    }

    @Test
    void theSidePanelIdsAndAboutOnlyOnExtractFromPaper() throws Exception {
        for (JsonNode step : steps()) {
            String slug = step.get("slug").asString();
            String html = html("/examples/" + slug);
            for (String id : PANEL_IDS) {
                assertThat(html).as(slug + ": #" + id).contains("id=\"" + id + "\"");
            }
            assertThat(html.contains(ABOUT)).as(slug + ": the about block").isEqualTo(slug.equals("extract-from-paper"));
        }
    }

    @Test
    void theCatalogAndThePdfLoadingPage() throws Exception {
        String catalog = html("/");
        assertThat(catalog).contains("<title>Java Backend Integration with Spring Boot · SurveyJS</title>");
        assertThat(catalog).contains("Step-by-Step Java Backend Integration with Spring Boot");
        assertThat(count(catalog, "rel=\"preload\" as=\"font\"")).isEqualTo(4);
        assertThat(html("/examples/server-pdf/loading")).contains("Generating the PDF...");
        for (String path : List.of("/", "/examples/save-response", "/examples/server-pdf/loading")) {
            assertThat(html(path)).as(path).contains("<link rel=\"canonical\" href=\"http://localhost" + path + "\">");
        }
    }

    @Test
    void anUnknownSlugIs404AndABadFormIdIs400() throws Exception {
        for (String path : List.of("/examples/no-such-example", "/examples/Save-Response", "/examples/save-response/extra")) {
            MvcResult r = mvc.perform(get(path).cookie(user("alice"))).andReturn();
            assertThat(r.getResponse().getStatus()).as(path).isEqualTo(404);
        }
        MvcResult bad = mvc.perform(get("/examples/edit-together").param("form", "bad id").cookie(user("alice"))).andReturn();
        assertThat(bad.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(bad)).contains("Invalid form ID");
        MvcResult tooLong = mvc.perform(get("/examples/edit-together").param("form", "x".repeat(65)).cookie(user("alice"))).andReturn();
        assertThat(tooLong.getResponse().getStatus()).isEqualTo(400);
        assertThat(html("/examples/edit-together?form=contact-team")).contains("window.SURVEYJS_PAGE.formId = \"contact-team\";");
        assertThat(html("/examples/edit-together")).contains("window.SURVEYJS_PAGE.formId = \"support-team\";");
        assertThat(html("/examples/fill-together?record=new")).contains("window.SURVEYJS_PAGE.record = \"new\";");
    }

    @Test
    void aScriptTagInAStoredDefinitionOrAQueryValueCantEndTheInlineScript() throws Exception {
        String original = pageConfig(html("/examples/extract-from-paper")).get("definition").toString();
        String definition = "{\"title\":\"</script><script>alert(1)</script>\",\"elements\":[{\"type\":\"text\",\"name\":\"q1\",\"title\":\"é & <b>\"}]}";
        Cookie alice = user("alice");
        assertThat(mvc.perform(put("/api/forms/work-order").cookie(alice).contentType(MediaType.APPLICATION_JSON).content(definition))
                .andReturn().getResponse().getStatus()).isEqualTo(204);
        try {
            String html = html("/examples/extract-from-paper");
            assertThat(html).doesNotContain("</script><script>alert(1)");
            Matcher config = Pattern.compile("<script>window\\.SURVEYJS_PAGE = (.*?)</script>").matcher(html);
            assertThat(config.find()).isTrue();
            assertThat(config.group(1)).doesNotContain("</");   // written as <\/script>
            assertThat(pageConfig(html).get("definition")).isEqualTo(json(definition));
        } finally {
            mvc.perform(put("/api/forms/work-order").cookie(alice).contentType(MediaType.APPLICATION_JSON).content(original));
        }
        String record = body(mvc.perform(get("/examples/fill-together").param("record", "</script><b>").cookie(alice)).andReturn());
        assertThat(record).doesNotContain("</script><b>");
        assertThat(record).contains("window.SURVEYJS_PAGE.record = \"<\\/script><b>\";");
    }

    @Test
    void theImportMapMapsEveryHighlightLanguageTheSharedModuleImports() throws Exception {
        Matcher language = Pattern.compile("from\\s+['\"](highlight\\.js/lib/languages/[a-z0-9-]+)['\"]").matcher(read("shared/ui/code-highlight.js"));
        List<String> languages = new ArrayList<>();
        while (language.find()) {
            languages.add(language.group(1));
        }
        assertThat(languages).contains("highlight.js/lib/languages/java", "highlight.js/lib/languages/javascript", "highlight.js/lib/languages/json");
        JsonNode imports = importMap(html("/examples/save-response")).get("imports");
        for (String specifier : languages) {
            assertThat(imports.has(specifier)).as(specifier).isTrue();
            assertThat(imports.get(specifier).asString()).startsWith("https://unpkg.com/@highlightjs/cdn-assets@11.12.0/es/languages/");
        }
        // and every other bare specifier the shared modules import
        try (var files = Files.walk(Path.of("shared"))) {
            List<Path> modules = files.filter(p -> p.toString().endsWith(".js") && (p.startsWith(Path.of("shared", "client")) || p.startsWith(Path.of("shared", "ui")))).toList();
            Pattern bare = Pattern.compile("(?:import|from)\\s+['\"]([^'\"./][^'\"]*)['\"]");
            for (Path module : modules) {
                Matcher specifier = bare.matcher(Files.readString(module));
                while (specifier.find()) {
                    if (specifier.group(1).contains("://")) {
                        continue;
                    }
                    assertThat(imports.has(specifier.group(1))).as(module + " imports " + specifier.group(1)).isTrue();
                }
            }
        }
    }

    // ---------- /shared and the raw paths, through Tomcat

    HttpResponse<byte[]> fetch(String path) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    @Test
    void sharedServesItsFourFoldersWithTheirTypesAndNothingElse() throws Exception {
        String[][] served = {
            {"/shared/ui/page-tabs.js", "text/javascript"}, {"/shared/client/host.js", "text/javascript"},
            {"/shared/samples/support-help.svg", "image/svg+xml"}, {"/shared/ui/fonts/lato/LatoLatin-Bold.woff2", "font/woff2"},
            {"/shared/definitions/save-response.json", "application/json"}, {"/shared/ui/theme.css", "text/css"},
            {"/css/site.css", "text/css"}, {"/shared/samples/work-order-scan.png", "image/png"},
        };
        for (String[] file : served) {
            HttpResponse<byte[]> r = fetch(file[0]);
            assertThat(r.statusCode()).as(file[0]).isEqualTo(200);
            assertThat(r.headers().firstValue("Content-Type").orElse("").split(";")[0]).as(file[0]).isEqualTo(file[1]);
            assertThat(r.headers().firstValue("Cache-Control").orElse("")).as(file[0]).isEqualTo("no-cache");
        }
        assertThat(fetch("/shared/samples/work-order-scan.png").body()).isEqualTo(png());
        // No .mjs file is served from shared/ today: the mapping a .mjs would get
        assertThat(servletContext.getMimeType("module.mjs")).isEqualTo("text/javascript");
        assertThat(servletContext.getMimeType("module.js")).isEqualTo("text/javascript");
        for (String path : List.of("/shared/seed/forms.json", "/shared/contract-tests/run.mjs", "/shared/CONTRACT", "/shared/SHA256SUMS",
                "/shared/ui/", "/shared/", "/shared/%2e%2e/pom.xml", "/shared/..%2fpom.xml", "/static/css/site.css", "/application.properties",
                "/templates/catalog.html")) {
            assertThat(fetch(path).statusCode()).as(path).isEqualTo(404);
        }
    }

    /** Sends a request line as raw bytes, as the contract test does: an HTTP client would resolve the dots. */
    String rawStatusLine(String path) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(10_000);
            OutputStream out = socket.getOutputStream();
            out.write(("GET " + path + " HTTP/1.1\r\nHost: 127.0.0.1:" + port + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            InputStream in = socket.getInputStream();
            String response = new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
            return response.substring(0, response.indexOf("\r\n"));
        }
    }

    @Test
    void theContractsRawTraversalPathsAnswer404() throws Exception {
        for (String path : List.of("/shared/../.env", "/shared/client/../../.env", "/shared/%2e%2e/.env", "/shared/..%2f.env",
                "/shared/ui/../../pom.xml", "/shared/client/..%2f..%2fpom.xml")) {
            assertThat(rawStatusLine(path)).as(path).startsWith("HTTP/1.1 404");
        }
        assertThat(rawStatusLine("/shared/ui/page-tabs.js")).startsWith("HTTP/1.1 200");
    }

    @Test
    void xForwardedProtoHttpsGivesHttpsCanonicalAndWssRelays() throws Exception {
        HttpResponse<String> proxied = HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/examples/fill-together"))
                .header("X-Forwarded-Proto", "https").header("Cookie", "demo_user=alice").build(), HttpResponse.BodyHandlers.ofString());
        assertThat(proxied.statusCode()).isEqualTo(200);
        assertThat(proxied.body()).containsPattern("<link rel=\"canonical\" href=\"https://127\\.0\\.0\\.1(:443)?/examples/fill-together\">");
        JsonNode relay = pageConfig(proxied.body()).get("relay");
        assertThat(relay.get("fill").get("url").asString()).startsWith("wss://127.0.0.1");
        assertThat(relay.get("edit").get("url").asString()).startsWith("wss://127.0.0.1");
        HttpResponse<String> catalog = HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/"))
                .header("X-Forwarded-Proto", "https").build(), HttpResponse.BodyHandlers.ofString());
        assertThat(catalog.body()).containsPattern("<link rel=\"canonical\" href=\"https://127\\.0\\.0\\.1(:443)?/\">");

        HttpResponse<String> plain = HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/examples/edit-together"))
                .header("Cookie", "demo_user=alice").build(), HttpResponse.BodyHandlers.ofString());
        assertThat(plain.body()).contains("<link rel=\"canonical\" href=\"http://127.0.0.1:" + port + "/examples/edit-together\">");
        assertThat(pageConfig(plain.body()).get("relay").get("edit").get("url").asString()).isEqualTo("ws://127.0.0.1:" + port);
        assertThat(pageConfig(plain.body()).get("relay").get("edit").get("port").isInt()).isTrue();
    }

    @Test
    void healthAnswersWithoutTheDatabase() throws Exception {
        HttpResponse<byte[]> up = fetch("/up");
        assertThat(up.statusCode()).isEqualTo(200);
        assertThat(json(new String(up.body(), StandardCharsets.UTF_8))).isEqualTo(json("{\"status\":\"ok\"}"));
    }

    /** Demo mode: the relays' about block (the invite link carries the sandbox) joins extract-from-paper's. */
    @Nested
    @TestPropertySource(properties = "surveyjs.demo-mode=true")
    class DemoMode {

        @Autowired
        MockMvc mvc;

        @Test
        void aboutRendersOnlyWhereTheReferenceRendersIt() throws Exception {
            MvcResult first = mvc.perform(get("/demo/stored/save-response")).andReturn();
            Cookie sid = new Cookie("demo_sid", cookieValue(setCookie(first.getResponse().getHeaders("Set-Cookie"), "demo_sid")));
            List<String> about = List.of("extract-from-paper", "fill-together", "edit-together");
            for (JsonNode step : steps()) {
                String slug = step.get("slug").asString();
                MvcResult r = mvc.perform(get("/examples/" + slug).cookie(user("alice"), sid)).andReturn();
                assertThat(r.getResponse().getStatus()).as(slug).isEqualTo(200);
                String html = body(r);
                assertThat(html.contains(ABOUT)).as(slug + ": the about block").isEqualTo(about.contains(slug));
                if (!slug.equals("extract-from-paper") && about.contains(slug)) {
                    assertThat(html).contains("The invite link carries your sandbox ID");
                }
                assertThat(pageConfig(html).get("demoMode").asBoolean()).isTrue();
                assertThat(pageConfig(html).get("sandboxId").asString()).isEqualTo(sid.getValue());
            }
        }
    }
}
