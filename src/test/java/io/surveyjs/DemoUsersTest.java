package io.surveyjs;

import java.util.List;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * The demo users: each page signs in as a user it offers (keeping the cookie's user when the page offers it, otherwise
 * resetting the cookie to the page's default); the API reads the cookie as it is; Bob's and signed-out saves answer
 * 403 with the edit-forms text; signed out, the progress endpoints answer 401.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class DemoUsersTest extends TestSupport {

    static final String EDIT_FORMS = "You don't have editor rights: only editors can change forms";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    MvcResult page(String path, String user) throws Exception {
        MockHttpServletRequestBuilder request = get(path);
        MvcResult r = mvc.perform(user == null ? request : request.cookie(user(user))).andReturn();
        assertThat(r.getResponse().getStatus()).as(path + " as " + user).isEqualTo(200);
        return r;
    }

    static String demoUserCookie(MvcResult r) {
        return setCookie(r.getResponse().getHeaders("Set-Cookie"), "demo_user");
    }

    @Test
    void aPageThatOffersTheUserKeepsIt() throws Exception {
        MvcResult kept = page("/examples/resume-progress", "bob");
        assertThat(pageConfig(body(kept)).get("demoUser").asString()).isEqualTo("bob");
        assertThat(pageConfig(body(kept)).get("user").get("name").asString()).isEqualTo("Bob Fischer");
        assertThat(demoUserCookie(kept)).isNull();
    }

    @Test
    void anyOtherPageSignsInAsItsDefaultAndResetsTheCookie() throws Exception {
        MvcResult reset = page("/examples/save-response", "bob");
        JsonNode config = pageConfig(body(reset));
        assertThat(config.get("demoUser").asString()).isEqualTo("alice");
        assertThat(config.get("user").get("key").asString()).isEqualTo("alice");
        String cookie = demoUserCookie(reset);
        assertThat(cookie).startsWith("demo_user=alice;");
        assertThat(cookie.toLowerCase()).contains("samesite=lax").contains("path=/").doesNotContain("httponly");   // host.js writes it back
        assertThat(pageConfig(body(page("/examples/creator-load-save", "none"))).get("demoUser").asString()).isEqualTo("alice");
        assertThat(pageConfig(body(page("/examples/creator-load-save", "mallory"))).get("demoUser").asString()).isEqualTo("alice");
    }

    @Test
    void aSignedOutVisitorGetsThePageDefault() throws Exception {
        MvcResult r = page("/examples/dashboard", null);
        assertThat(pageConfig(body(r)).get("user").get("name").asString()).isEqualTo("Alice Martin");
        assertThat(demoUserCookie(r)).startsWith("demo_user=alice;");
        assertThat(pageConfig(body(page("/examples/resume-progress", null))).get("demoUser").asString()).isEqualTo("alice");
    }

    @Test
    void claimSignsInLikeItsExamplePage() throws Exception {
        MvcResult bob = page("/claim", "bob");
        assertThat(body(bob)).contains("window.FORM_VARIABLES = {\"user\":{\"name\":\"Bob Fischer\",\"plan\":\"basic\"}};");
        assertThat(pageConfig(body(bob)).get("user").get("key").asString()).isEqualTo("bob");
        assertThat(pageConfig(body(bob)).get("demoUser").asString()).isEqualTo("bob");
        assertThat(demoUserCookie(bob)).isNull();
        MvcResult signedOut = page("/claim", null);
        assertThat(body(signedOut)).contains("window.FORM_VARIABLES = {\"user\":{\"name\":\"Alice Martin\",\"plan\":\"premium\"}};");
        assertThat(pageConfig(body(signedOut)).get("demoUser").asString()).isEqualTo("alice");
        assertThat(demoUserCookie(signedOut)).startsWith("demo_user=alice;");
    }

    @Test
    void theSwitchAppearsOnlyWhereTheUserChangesTheResult() throws Exception {
        String[][] switches = {
            {"load-definition-variables-data", "Alice (premium)", "Bob (basic)"},
            {"resume-progress", "Alice", "Bob"},
            {"creator-load-save", "Alice (can save schemas)", "Bob (cannot save schemas)"},
        };
        for (String[] page : switches) {
            String html = body(page("/examples/" + page[0], "alice"));
            assertThat(html).as(page[0]).contains("id=\"demo-user\"");
            assertThat(html).as(page[0]).contains(">" + page[1] + "</option>").contains(">" + page[2] + "</option>");
        }
        for (String slug : List.of("save-response", "store-files", "dashboard", "variable-presets", "fill-together")) {
            assertThat(body(page("/examples/" + slug, "alice"))).as(slug).doesNotContain("id=\"demo-user\"");
        }
    }

    @Test
    void bobAndSignedOutSavesAnswer403() throws Exception {
        for (String user : new String[] {"bob", null, "none", "mallory"}) {
            for (String path : List.of("/api/forms/support", "/api/variable-presets/support")) {
                MockHttpServletRequestBuilder request = put(path).contentType(MediaType.APPLICATION_JSON).content("[]");
                MvcResult r = mvc.perform(user == null ? request : request.cookie(user(user))).andReturn();
                assertError(r, 403, EDIT_FORMS);
            }
        }
        JsonNode support = json(jdbc.sql("SELECT json FROM forms WHERE key = 'support'").query(String.class).single());
        assertThat(support.isObject()).isTrue();   // unchanged
    }

    @Test
    void progressAnswers401SignedOutAndIsPerUser() throws Exception {
        String[][] calls = {{"GET", "Sign in to load progress"}, {"PUT", "Sign in to save progress"}, {"DELETE", "Sign in to clear progress"}};
        for (String user : new String[] {null, "none", "mallory"}) {
            for (String[] call : calls) {
                MockHttpServletRequestBuilder request = switch (call[0]) {
                    case "GET" -> get("/api/progress/onboarding");
                    case "PUT" -> put("/api/progress/onboarding").contentType(MediaType.APPLICATION_JSON).content("{}");
                    default -> delete("/api/progress/onboarding");
                };
                assertError(mvc.perform(user == null ? request : request.cookie(user(user))).andReturn(), 401, call[1]);
            }
        }
        assertThat(mvc.perform(put("/api/progress/demo-users").cookie(user("alice")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"data\":{\"who\":\"alice\"}}")).andReturn().getResponse().getStatus()).isEqualTo(204);
        MvcResult bob = mvc.perform(get("/api/progress/demo-users").cookie(user("bob"))).andReturn();
        assertThat(body(bob)).isEqualTo("null");
        assertThat(bob.getResponse().getContentType()).startsWith("application/json");
        assertThat(mvc.perform(delete("/api/progress/demo-users").cookie(user("bob"))).andReturn().getResponse().getStatus()).isEqualTo(204);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM progress WHERE key = '1:demo-users'").query(Integer.class).single()).isEqualTo(1);
        assertThat(json(mvc.perform(get("/api/progress/demo-users").cookie(user("alice"))).andReturn()))
                .isEqualTo(json("{\"data\":{\"who\":\"alice\"}}"));
        assertThat(mvc.perform(delete("/api/progress/demo-users").cookie(user("alice"))).andReturn().getResponse().getStatus()).isEqualTo(204);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM progress WHERE key = '1:demo-users'").query(Integer.class).single()).isZero();
    }
}
