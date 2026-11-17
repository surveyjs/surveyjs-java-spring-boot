package io.surveyjs;

import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import io.surveyjs.config.SurveyJsProperties;
import io.surveyjs.demo.SandboxPruner;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Demo mode: each visitor (demo_sid) gets a private, seeded H2 database and uploads folder; an invite link
 * (?join=) adopts the inviter's sandbox, an unknown one says so; the pruner closes a sandbox's database, then deletes it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestPropertySource(properties = "surveyjs.demo-mode=true")
class SandboxTest extends TestSupport {

    @Autowired
    MockMvc mvc;

    @Autowired
    SurveyJsProperties settings;

    @Autowired
    SandboxPruner pruner;

    @LocalServerPort
    int port;

    /** A new visitor: no cookie, so the server issues a demo_sid. */
    String newVisitor() throws Exception {
        MvcResult r = mvc.perform(get("/demo/stored/save-response")).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        String cookie = setCookie(r.getResponse().getHeaders("Set-Cookie"), "demo_sid");
        assertThat(cookie).isNotNull();
        assertThat(cookie.toLowerCase()).contains("httponly").contains("samesite=lax").contains("path=/");
        String sid = cookieValue(cookie);
        assertThat(sid).matches("^[0-9a-f]{32}$");
        return sid;
    }

    static Cookie[] as(String sid) {
        return new Cookie[] {user("alice"), new Cookie("demo_sid", sid)};
    }

    String stored(String sid) throws Exception {
        return body(mvc.perform(get("/demo/stored/save-response").cookie(as(sid))).andReturn());
    }

    int save(String sid, String marker) throws Exception {
        MvcResult r = mvc.perform(post("/api/responses").cookie(as(sid)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"formId\":\"contact\",\"data\":{\"message\":\"" + marker + "\"}}")).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(201);
        return json(r).get("id").asInt();
    }

    @Test
    void twoVisitorsGetSeparateDatabases() throws Exception {
        String first = newVisitor();
        String second = newVisitor();
        assertThat(first).isNotEqualTo(second);
        String marker = "sandboxed-" + UUID.randomUUID();
        save(first, marker);
        assertThat(stored(first)).contains(marker);
        assertThat(stored(second)).doesNotContain(marker);
        for (String sid : new String[] {first, second}) {
            assertThat(settings.sandboxDir().resolve(sid).resolve("database").resolve("surveyjs.mv.db")).isRegularFile();
        }
        // Each sandbox is seeded: both have the demo users and the seeded forms
        for (String sid : new String[] {first, second}) {
            MvcResult form = mvc.perform(get("/api/forms/support").cookie(as(sid))).andReturn();
            assertThat(form.getResponse().getStatus()).isEqualTo(200);
        }
        assertThat(body(mvc.perform(get("/api/responses").param("formId", "contact")).andReturn())).doesNotContain(marker);   // a third, new visitor
    }

    @Test
    void twoVisitorsGetSeparateUploadsFolders() throws Exception {
        String first = newVisitor();
        String second = newVisitor();
        HttpResponse<String> r = postMultipart(port, "/api/files", "demo_user=alice; demo_sid=" + first,
                new Object[] {"files[]", "receipt.png", "image/png", png()});
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        String id = json(r.body()).get(0).asString();
        assertThat(settings.sandboxDir().resolve(first).resolve("uploads").resolve(id)).isRegularFile();
        assertThat(settings.sandboxDir().resolve(second).resolve("uploads").resolve(id)).doesNotExist();
        assertThat(settings.uploadsDir().resolve(id)).doesNotExist();
        assertThat(mvc.perform(get("/api/files/" + id).cookie(as(first))).andReturn().getResponse().getStatus()).isEqualTo(200);
        assertThat(mvc.perform(get("/api/files/" + id).cookie(as(second))).andReturn().getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void anInviteLinkAdoptsThatSandbox() throws Exception {
        String inviter = newVisitor();
        String guest = newVisitor();
        String marker = "shared-" + UUID.randomUUID();
        save(inviter, marker);
        MvcResult joined = mvc.perform(get("/examples/fill-together").param("join", inviter).cookie(as(guest))).andReturn();
        assertThat(joined.getResponse().getStatus()).isEqualTo(200);
        assertThat(cookieValue(setCookie(joined.getResponse().getHeaders("Set-Cookie"), "demo_sid"))).isEqualTo(inviter);
        assertThat(pageConfig(body(joined)).get("sandboxId").asString()).isEqualTo(inviter);
        assertThat(body(joined)).doesNotContain("That invite link has expired");
        assertThat(stored(inviter)).contains(marker);   // what the guest now sees, with the adopted cookie
        // Joining the sandbox you already have issues no new cookie
        MvcResult again = mvc.perform(get("/examples/fill-together").param("join", inviter).cookie(as(inviter))).andReturn();
        assertThat(setCookie(again.getResponse().getHeaders("Set-Cookie"), "demo_sid")).isNull();
    }

    @Test
    void anUnknownInviteGetsAFreshSandboxAndTheNotice() throws Exception {
        String visitor = newVisitor();
        String unknown = "0123456789abcdef0123456789abcdef";
        for (String join : new String[] {unknown, "../../etc", "ABCDEF0123456789ABCDEF0123456789"}) {
            MvcResult r = mvc.perform(get("/examples/edit-together").param("join", join).cookie(as(visitor))).andReturn();
            assertThat(r.getResponse().getStatus()).isEqualTo(200);
            assertThat(body(r)).contains("That invite link has expired, so you have a fresh copy of the demo data.");
            String fresh = cookieValue(setCookie(r.getResponse().getHeaders("Set-Cookie"), "demo_sid"));
            assertThat(fresh).matches("^[0-9a-f]{32}$").isNotEqualTo(unknown).isNotEqualTo(visitor);
        }
        assertThat(settings.sandboxDir().resolve(unknown)).doesNotExist();
        MvcResult plain = mvc.perform(get("/examples/edit-together").cookie(as(visitor))).andReturn();
        assertThat(body(plain)).doesNotContain("That invite link has expired");
    }

    @Test
    void anInvalidSidCookieIsReplaced() throws Exception {
        MvcResult r = mvc.perform(get("/demo/stored/save-response").cookie(new Cookie("demo_sid", "../../database"))).andReturn();
        assertThat(cookieValue(setCookie(r.getResponse().getHeaders("Set-Cookie"), "demo_sid"))).matches("^[0-9a-f]{32}$");
    }

    @Test
    void thePrunerClosesThenDeletesAnUnusedSandbox() throws Exception {
        String sid = newVisitor();
        save(sid, "to be pruned");
        Path folder = settings.sandboxDir().resolve(sid);
        assertThat(folder.resolve("database").resolve("surveyjs.mv.db")).isRegularFile();
        assertThat(folder.resolve("last-used")).isRegularFile();

        pruner.prune(Instant.now());   // used just now: kept
        assertThat(folder).isDirectory();

        int removed = pruner.prune(Instant.now().plus(Duration.ofHours(25)));   // open H2 files: Windows can't delete them unless closed first
        assertThat(removed).isGreaterThanOrEqualTo(1);
        assertThat(folder).doesNotExist();
        assertThat(Files.exists(settings.sandboxDir())).isTrue();

        // The same cookie later gets a new, freshly seeded sandbox
        assertThat(stored(sid)).doesNotContain("to be pruned");
        assertThat(folder.resolve("database").resolve("surveyjs.mv.db")).isRegularFile();
    }
}
