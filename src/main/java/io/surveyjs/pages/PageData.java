package io.surveyjs.pages;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import io.surveyjs.config.SurveyJsProperties;
import io.surveyjs.demo.Manifest;
import io.surveyjs.demo.SandboxContext;
import io.surveyjs.security.User;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import tools.jackson.core.util.DefaultIndenter;
import tools.jackson.core.util.DefaultPrettyPrinter;
import tools.jackson.core.util.Separators;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Demo only: what the example pages compute before they render. The reference's Blade views do this in {@code @php}
 * blocks (the records to pick from, the edit-together form check and its 400, server-pdf's claims, ?form, ?version);
 * here it happens before the template renders, so templates neither query nor set a status. Also the page config,
 * {@code window.SURVEYJS_PAGE}, which shared/client/host.js and the example modules read.
 */
@Component
public class PageData {

    private static final Pattern FORM_ID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");
    private static final Set<String> CREATOR = Set.of("creator-load-save", "creator-uploads", "ai-translation",
            "variable-presets", "edit-together", "lint-definition");

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final SurveyJsProperties settings;
    private final Manifest manifest;

    public PageData(JdbcClient jdbc, JsonMapper json, SurveyJsProperties settings, Manifest manifest) {
        this.jdbc = jdbc;
        this.json = json;
        this.settings = settings;
        this.manifest = manifest;
    }

    /** The page's own model attributes: its theme, the layout's switches and the values of its @php block. */
    public Map<String, Object> forPage(String slug, HttpServletRequest request, User user) {
        Map<String, Object> model = new LinkedHashMap<>();
        boolean formMode = request.getParameter("form") != null;
        String theme = slug.equals("dashboard") ? "dashboard"
                : CREATOR.contains(slug) && !(formMode && slug.startsWith("creator-")) ? "creator" : "survey";
        model.put("theme", theme);
        // Blade's @hasSection: every page but the Dashboard fills `actions`; `about` is extract-from-paper's, and the
        // relays' in demo mode (the invite link carries the sandbox)
        model.put("hasActions", !slug.equals("dashboard"));
        model.put("hasAbout", slug.equals("extract-from-paper")
                || (settings.demoMode() && (slug.equals("fill-together") || slug.equals("edit-together"))));
        switch (slug) {
            case "load-definition-variables-data" -> {
                model.put("optionA", false);
                model.put("record", param(request, "record", ""));
                model.put("records", records("claim", "customer_email", user == null ? "" : user.email()));
            }
            case "fill-together" -> {
                List<Map<String, String>> records = records("job-sheet", "customer", null);
                String record = param(request, "record", records.isEmpty() ? "new" : records.get(0).get("id"));
                model.put("records", records);
                model.put("record", record);
                model.put("known", records.stream().anyMatch(r -> r.get("id").equals(record)));
            }
            case "edit-together" -> {
                String formId = param(request, "form", "support-team");
                if (!FORM_ID.matcher(formId).matches()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid form ID");
                }
                List<Map<String, String>> forms = collaborativeForms();
                model.put("forms", forms);
                model.put("formId", formId);
                model.put("known", forms.stream().anyMatch(f -> f.get("id").equals(formId)));
            }
            case "relational-storage" -> model.put("version", "v2".equals(request.getParameter("version")) ? "v2" : "v1");
            case "server-pdf" -> model.put("claims", claims());
            case "creator-load-save", "creator-uploads" -> model.put("formMode", formMode);
            default -> { }
        }
        return model;
    }

    /** window.SURVEYJS_PAGE: the page's slug, versions, user, relays, switches and its JSON definitions. */
    public Map<String, Object> pageConfig(Map<String, Object> step, User user) {
        Map<String, Object> config = new LinkedHashMap<>();
        String slug = (String) step.get("slug");
        config.put("slug", slug);
        config.put("version", manifest.surveyjsVersion());
        config.put("licenseKey", settings.licenseKey());
        config.put("demoUser", user == null ? "none" : user.key());
        if (user == null) {
            config.put("user", null);
        } else {
            Map<String, Object> pageUser = new LinkedHashMap<>();
            pageUser.put("key", user.key());
            pageUser.put("name", user.name());
            pageUser.put("plan", user.plan());
            pageUser.put("isEditor", user.editor());
            config.put("user", pageUser);
        }
        // The relays run in this app: the page's own origin, ws or wss as the visitor sees it (X-Forwarded-Proto)
        String origin = ServletUriComponentsBuilder.fromCurrentContextPath().replacePath(null).replaceQuery(null).build().toUriString();
        String relay = origin.replaceFirst("^http", "ws");
        Map<String, Object> relays = new LinkedHashMap<>();
        relays.put("fill", relayConfig(settings.relayUrl() != null ? settings.relayUrl() : relay));
        relays.put("edit", relayConfig(settings.editRelayUrl() != null ? settings.editRelayUrl() : relay));
        config.put("relay", relays);
        config.put("demoMode", settings.demoMode());
        config.put("sandboxId", SandboxContext.id());
        Map<String, Object> switches = new LinkedHashMap<>();
        switches.put("validateResponses", settings.validateResponses());
        switches.put("lintDefinitions", settings.lintDefinitions());
        config.put("switches", switches);
        Manifest.files(step).forEach((role, path) -> {
            if (role.startsWith("definition")) {
                config.put(role, readFile(path));
            }
        });
        if (slug.equals("extract-from-paper")) {   // the stored work-order form: the blank PDF and the extraction use it too
            String form = jdbc.sql("SELECT json FROM forms WHERE key = 'work-order'").query(String.class).optional()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown work order form"));
            config.put("definition", json.readTree(form));
        }
        return config;
    }

    private Map<String, Object> relayConfig(String url) {
        Map<String, Object> relay = new LinkedHashMap<>();
        relay.put("url", url);
        relay.put("port", settings.appPort());
        return relay;
    }

    /** Saved responses to pick from: "#<id> · <label>". H2 can't filter inside the JSON text, so the customer's email
     *  is compared after reading each document. */
    private List<Map<String, String>> records(String formId, String labelKey, String customerEmail) {
        List<Map<String, String>> records = new ArrayList<>();
        for (Map<String, Object> row : jdbc.sql("SELECT id, data FROM responses WHERE form_id = ? ORDER BY id").param(formId).query().listOfRows()) {
            JsonNode data = json.readTree((String) row.get("data"));
            if (customerEmail != null && !customerEmail.equals(data.path("customer_email").asString(null))) {
                continue;
            }
            JsonNode label = data.path(labelKey);
            String text = label.isMissingNode() || label.isNull() ? "" : label.isString() ? label.asString() : label.toString();
            records.add(Map.of("id", String.valueOf(row.get("id")), "label", "#" + row.get("id") + " · " + text));
            if (records.size() == 10) {
                break;
            }
        }
        return records;
    }

    /** The forms edit-together offers: the seeded *-team forms and the team-<uuid> ones visitors created. */
    private List<Map<String, String>> collaborativeForms() {
        List<Map<String, String>> forms = new ArrayList<>();
        for (Map<String, Object> row : jdbc.sql("SELECT key, json FROM forms WHERE key LIKE '%-team' OR key LIKE 'team-%' ORDER BY key").query().listOfRows()) {
            JsonNode title = json.readTree((String) row.get("json")).path("title");
            String label = title.isString() ? title.asString() : title.path("default").isString() ? title.path("default").asString() : null;
            forms.add(Map.of("id", (String) row.get("key"), "label", label == null || label.isEmpty() ? "Untitled form" : label));
        }
        return forms;
    }

    /** server-pdf: the 10 latest claims, each with its answers as pretty JSON. */
    private List<Map<String, Object>> claims() {
        List<Map<String, Object>> claims = new ArrayList<>();
        for (Map<String, Object> row : jdbc.sql("SELECT id, data FROM responses WHERE form_id = 'claim' ORDER BY id DESC LIMIT 10").query().listOfRows()) {
            JsonNode answers = json.readTree((String) row.get("data"));
            JsonNode email = answers.path("customer_email");
            String label = email.isString() || email.isNumber() ? email.asString() : "No email provided";
            claims.add(Map.of("id", row.get("id"), "label", label, "json", pretty(answers)));
        }
        return claims;
    }

    /** JSON indented by four spaces, as PHP's JSON_PRETTY_PRINT writes it. */
    String pretty(JsonNode value) {
        DefaultPrettyPrinter printer = new DefaultPrettyPrinter(Separators.createDefaultInstance()
                .withObjectNameValueSpacing(Separators.Spacing.AFTER)
                .withObjectEmptySeparator("").withArrayEmptySeparator(""))
                .withObjectIndenter(new DefaultIndenter("    ", "\n"))
                .withArrayIndenter(new DefaultIndenter("    ", "\n"));
        return json.writer().with(printer).writeValueAsString(value);
    }

    private JsonNode readFile(String path) {
        try {
            return json.readTree(Files.readString(Path.of(path)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String param(HttpServletRequest request, String name, String fallback) {
        String value = request.getParameter(name);
        return value == null ? fallback : value;
    }
}
