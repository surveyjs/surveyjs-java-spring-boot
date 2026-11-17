package io.surveyjs.examples;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import io.surveyjs.pages.PageController;
import io.surveyjs.security.User;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Load JSON Schemas, Variables, and Answers: GET /claim (Option A) and GET /api/forms/{id} (Option B).
 *
 * <p>Creator, the Dashboard and the other Creator examples read .definition from the same GET /api/forms/{id}.
 */
@Controller
public class LoadDefinitionVariablesDataController {

    private static final Path DEFINITION = Path.of("shared", "definitions", "load-definition-variables-data.json");

    /** The variables the form reads as {user.name} and {user.plan}: never saved with the response. */
    public record Variables(UserVariables user) {
        static Variables of(User user) {
            return new Variables(new UserVariables(user == null ? null : user.name(), user == null ? null : user.plan()));
        }
    }

    public record UserVariables(String name, String plan) {
    }

    /** Option B's answer: the definition, the variables and the previous answers ({} without them). */
    public record FormAnswer(JsonNode definition, Variables variables, JsonNode data) {
    }

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final PageController pages;

    public LoadDefinitionVariablesDataController(JdbcClient jdbc, JsonMapper json, PageController pages) {
        this.jdbc = jdbc;
        this.json = json;
        this.pages = pages;
    }

    // #region sjs:load-definition-variables-data.server
    // Option A: Embed the schema and user variables in the HTML response
    @GetMapping("/claim")
    public String claimPage(@AuthenticationPrincipal User user, Model model) throws IOException {
        model.addAttribute("formDefinition", json.readTree(Files.readString(DEFINITION)));
        model.addAttribute("formVariables", Variables.of(user));
        // The template writes both with th:inline="javascript", which escapes them for an inline <script>
        return pages.render("load-definition-variables-data", model.addAttribute("optionA", true));
    }

    // Option B: Return the definition, variables, and previous answers in one response
    @GetMapping("/api/forms/{id}")
    @ResponseBody
    public FormAnswer loadForm(@PathVariable String id, @RequestParam(required = false) Long record, @AuthenticationPrincipal User user) {
        String form = jdbc.sql("SELECT json FROM forms WHERE key = ?").param(id).query(String.class).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown form"));
        Optional<JsonNode> saved = jdbc.sql("SELECT data FROM responses WHERE id = ?" + (id.equals("claim-request") ? " AND form_id = 'claim'" : ""))
                .param(record).query(String.class).optional().map(json::readTree);
        if (id.equals("claim-request")) {   // only the signed-in customer's own claims
            // H2 can't query inside JSON text, so the owner check reads the document
            saved = saved.filter(data -> user != null && user.email().equals(data.path("customer_email").asString(null)));
        }
        return new FormAnswer(json.readTree(form), Variables.of(user), saved.orElse(json.createObjectNode()));   // {} without a record
    }
    // #endregion
}
