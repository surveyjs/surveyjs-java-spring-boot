package io.surveyjs;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Map;

import io.surveyjs.config.SurveyJsProperties;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

/**
 * Store Files Separately: the whole batch is checked before a byte is stored, the type comes from the bytes, names
 * may be non-ASCII, a download can't run as the site, and a signed-out or unknown download is a 404.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class StoreFilesTest extends TestSupport {

    static final byte[] PDF = "%PDF-1.4\n% Demo receipt\n%%EOF\n".getBytes(StandardCharsets.ISO_8859_1);
    static final byte[] HTML = "<!DOCTYPE html><script>alert(document.cookie)</script>".getBytes(StandardCharsets.UTF_8);

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    SurveyJsProperties settings;

    @LocalServerPort
    int port;

    static MockMultipartFile file(String name, byte[] content, String type) {
        return new MockMultipartFile("files[]", name, type, content);
    }

    MvcResult upload(MockMultipartFile... files) throws Exception {
        var request = multipart("/api/files");
        for (MockMultipartFile file : files) {
            request.file(file);
        }
        return mvc.perform(request.cookie(user("alice"))).andReturn();
    }

    int storedFiles() throws Exception {
        long onDisk = Files.isDirectory(settings.uploadsDir()) ? Files.list(settings.uploadsDir()).count() : 0;
        int rows = jdbc.sql("SELECT COUNT(*) FROM files").query(Integer.class).single();
        assertThat(onDisk).isEqualTo(rows);
        return rows;
    }

    static byte[] big() throws Exception {
        return Arrays.copyOf(png(), 5 * 1024 * 1024 + 1);
    }

    @Test
    void aBatchIsStoredInOrderAndDownloadsTheSameBytes() throws Exception {
        MvcResult r = upload(file("one.pdf", PDF, "application/pdf"), file("two.png", png(), "image/png"));
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        JsonNode ids = json(r);
        assertThat(ids.isArray()).isTrue();
        assertThat(ids.size()).isEqualTo(2);
        Object[][] expected = {{ids.get(0).asString(), PDF, "application/pdf"}, {ids.get(1).asString(), png(), "image/png"}};
        for (Object[] file : expected) {
            MvcResult d = mvc.perform(get("/api/files/" + file[0]).cookie(user("alice"))).andReturn();
            assertThat(d.getResponse().getStatus()).isEqualTo(200);
            assertThat(d.getResponse().getContentAsByteArray()).isEqualTo((byte[]) file[1]);
            assertThat(d.getResponse().getContentType()).isEqualTo(file[2]);
            assertThat(d.getResponse().getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
            assertThat(d.getResponse().getHeader("Content-Security-Policy")).isEqualTo("sandbox");
            assertThat(settings.uploadsDir().resolve((String) file[0])).isRegularFile();   // named by id, no extension
        }
        assertThat(mvc.perform(get("/api/files/" + ids.get(0).asString()).cookie(user("bob"))).andReturn().getResponse().getStatus())
                .isEqualTo(200);   // any signed-in user may read
    }

    @Test
    void oneBadFileRefusesTheWholeBatch() throws Exception {
        int before = storedFiles();
        assertError(upload(file("ok.png", png(), "image/png"), file("page.png", HTML, "image/png")), 422,
                "page.png is not a PNG, JPEG, WebP, GIF or PDF file");
        assertError(upload(file("ok.png", png(), "image/png"), file("big.png", big(), "image/png")), 422, "Each file must be 5 MB or less");
        assertError(upload(file("page.html", HTML, "text/html")), 422, "page.html is not a PNG, JPEG, WebP, GIF or PDF file");
        assertError(upload(file("big.png", big(), "image/png")), 422, "Each file must be 5 MB or less");
        MvcResult wrongField = mvc.perform(multipart("/api/files").file(new MockMultipartFile("files", "ok.png", "image/png", png()))
                .cookie(user("alice"))).andReturn();
        assertError(wrongField, 422, "Upload one or more files in a field named \"files[]\"");
        assertThat(storedFiles()).isEqualTo(before);
        assertThat(upload(file("five.png", Arrays.copyOf(png(), 5 * 1024 * 1024), "image/png")).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void theTypeComesFromTheBytesNeverTheClient() throws Exception {
        String id = json(upload(file("receipt.png", png(), "text/html"))).get(0).asString();
        assertThat(jdbc.sql("SELECT type FROM files WHERE id = ?").param(id).query(String.class).single()).isEqualTo("image/png");
        String pdf = json(upload(file("receipt.bin", PDF, "application/octet-stream"))).get(0).asString();
        assertThat(jdbc.sql("SELECT type FROM files WHERE id = ?").param(pdf).query(String.class).single()).isEqualTo("application/pdf");
    }

    @Test
    void aNonAsciiFileNameIsKeptOverRealHttp() throws Exception {
        // Through Tomcat's own multipart parsing, as a browser sends it: the name in UTF-8
        String name = "квитанция-0.png";
        HttpResponse<String> r = postMultipart(port, "/api/files", "demo_user=alice", new Object[] {"files[]", name, "image/png", png()});
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        String id = json(r.body()).get(0).asString();
        Map<String, Object> row = jdbc.sql("SELECT name, type FROM files WHERE id = ?").param(id).query().singleRow();
        assertThat(row).containsEntry("name", name).containsEntry("type", "image/png");
    }

    @Test
    void anHtmlFileIsServedWithNosniffAndSandbox() throws Exception {
        // The upload refuses HTML, so put one in place the way another code path might have stored it
        String id = "html-" + System.nanoTime();
        jdbc.sql("INSERT INTO files (id, name, type) VALUES (?, 'page.html', 'text/html')").param(id).update();
        Files.createDirectories(settings.uploadsDir());
        Files.write(settings.uploadsDir().resolve(id), HTML);
        MvcResult d = mvc.perform(get("/api/files/" + id).cookie(user("alice"))).andReturn();
        assertThat(d.getResponse().getStatus()).isEqualTo(200);
        assertThat(d.getResponse().getContentType()).startsWith("text/html");
        assertThat(d.getResponse().getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(d.getResponse().getHeader("Content-Security-Policy")).isEqualTo("sandbox");
    }

    @Test
    void signedOutAndUnknownDownloadsAnswer404() throws Exception {
        String id = json(upload(file("a.png", png(), "image/png"))).get(0).asString();
        assertError(mvc.perform(get("/api/files/" + id)).andReturn(), 404, "Not found");
        assertError(mvc.perform(get("/api/files/" + id).cookie(user("none"))).andReturn(), 404, "Not found");
        assertError(mvc.perform(get("/api/files/no-such-file").cookie(user("alice"))).andReturn(), 404, "Not found");
    }

    /** Demo mode: every file over 5 MB answers 413 before any other check of the batch, and uploads go to the sandbox. */
    @Nested
    @TestPropertySource(properties = "surveyjs.demo-mode=true")
    class DemoMode {

        @Autowired
        MockMvc mvc;

        @Autowired
        SurveyJsProperties settings;

        @LocalServerPort
        int port;

        @Test
        void theDemoCapsEveryUploadAt5MbFirst() throws Exception {
            // Over real HTTP: the cap is an interceptor that sees Spring's MultipartHttpServletRequest, which MockMvc's
            // multipart request (wrapped by Spring Security) is not
            MvcResult first = mvc.perform(get("/api/offices")).andReturn();
            String sid = cookieValue(setCookie(first.getResponse().getHeaders("Set-Cookie"), "demo_sid"));
            String cookies = "demo_user=alice; demo_sid=" + sid;
            HttpResponse<String> r = postMultipart(port, "/api/files", cookies, new Object[] {"files[]", "page.png", "image/png", HTML},
                    new Object[] {"files[]", "big.png", "image/png", big()});
            assertThat(r.statusCode()).as(r.body()).isEqualTo(413);
            assertError(r.body(), "Files over 5 MB are not accepted on the demo");
            r = postMultipart(port, "/api/work-orders/extract", cookies, new Object[] {"scan", "big.png", "image/png", big()});
            assertThat(r.statusCode()).as(r.body()).isEqualTo(413);
            assertError(r.body(), "Files over 5 MB are not accepted on the demo");
            r = postMultipart(port, "/api/files", cookies, new Object[] {"files[]", "ok.png", "image/png", png()},
                    new Object[] {"files[]", "page.png", "image/png", HTML});
            assertThat(r.statusCode()).as(r.body()).isEqualTo(422);
            r = postMultipart(port, "/api/files", cookies, new Object[] {"files[]", "ok.png", "image/png", png()});
            assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
            String id = json(r.body()).get(0).asString();
            assertThat(settings.sandboxDir().resolve(sid).resolve("uploads").resolve(id)).isRegularFile();   // the visitor's folder
            assertThat(settings.uploadsDir().resolve(id)).doesNotExist();
        }
    }
}
