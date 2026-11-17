package io.surveyjs.pages;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import io.surveyjs.config.SurveyJsProperties;
import io.surveyjs.demo.CodePanel;
import io.surveyjs.demo.DemoUsers;
import io.surveyjs.demo.Manifest;
import io.surveyjs.demo.SandboxFilter;
import io.surveyjs.security.User;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * Demo only: the catalog and the example pages around the examples. The integration code is in
 * {@code io.surveyjs.examples}; these handlers only show it running.
 */
@Controller
public class PageController {

    static final String TITLE = "Java Backend Integration with Spring Boot";
    static final String HEADER = "Step-by-Step Java Backend Integration with Spring Boot";
    private static final Pattern SLUG = Pattern.compile("^[a-z0-9-]+$");

    /** The catalog's sections, in order, with their intros and hero icons. */
    private static final List<String[]> SECTIONS = List.of(
            new String[] {"Run Forms", "preview", "SurveyJS Form Library displays forms in the browser. Start with a Java endpoint that saves submitted answers, then add the workflows your application needs."},
            new String[] {"Analyze Results with Dashboard", "graph", "SurveyJS Dashboard lets users analyze responses with interactive charts in the browser. Your Java backend supplies the saved responses."},
            new String[] {"Edit Forms in Survey Creator", "embed-creator", "Survey Creator lets users build and edit forms in the browser. Your Java backend loads and saves form JSON schemas and checks that users have permission to make changes."},
            new String[] {"Run Server-Side Workflows", "server", "Use a Node.js service alongside your Java application to run SurveyJS workflows: validate responses, check form schemas, generate PDFs, and extract answers from documents."});

    private final Manifest manifest;
    private final PageData pageData;
    private final CodePanel codePanel;
    private final Frontend frontend;
    private final SurveyJsProperties settings;

    public PageController(Manifest manifest, PageData pageData, CodePanel codePanel, Frontend frontend, SurveyJsProperties settings) {
        this.manifest = manifest;
        this.pageData = pageData;
        this.codePanel = codePanel;
        this.frontend = frontend;
        this.settings = settings;
    }

    @GetMapping("/")
    public String catalog(Model model) {
        site(model);
        Map<String, List<Map<String, Object>>> groups = new LinkedHashMap<>();
        for (Map<String, Object> step : manifest.steps()) {
            groups.computeIfAbsent((String) step.get("section"), section -> new ArrayList<>()).add(step);
        }
        List<Map<String, String>> sections = new ArrayList<>();
        for (String[] section : SECTIONS) {
            sections.add(Map.of("title", section[0], "icon", section[1], "intro", section[2],
                    "anchor", section[0].toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "")));
        }
        model.addAttribute("groups", groups);
        model.addAttribute("sections", sections);
        return "catalog";
    }

    @GetMapping("/examples/server-pdf/loading")
    public String pdfLoading(Model model) {
        site(model);
        return "examples/pdf-loading";
    }

    @GetMapping("/examples/{slug}")
    public String example(@PathVariable String slug, Model model) {
        if (!SLUG.matcher(slug).matches() || manifest.step(slug).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown example");
        }
        return render(slug, model);
    }

    /**
     * Renders an example page: the page config, the Code tab, the page's own data, then its template. The demo user
     * is already signed in for the page (demo/PageUserInterceptor). /claim, Option A of
     * load-definition-variables-data, calls this with the definition and the variables in the model.
     */
    public String render(String slug, Model model) {
        Map<String, Object> step = manifest.step(slug).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        HttpServletRequest request = ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes()).getRequest();
        User user = currentUser();
        site(model);
        Map<String, Object> page = pageData.forPage(slug, request, user);
        Map<String, Object> config = pageData.pageConfig(step, user);
        String licence = (String) step.get("licence");
        model.addAttribute("step", step);
        model.addAttribute("page", config);
        model.addAttribute("importMap", frontend.importMap());
        model.addAttribute("highlightTheme", Frontend.HIGHLIGHT_THEME);
        model.addAttribute("users", DemoUsers.forPage(slug));
        model.addAttribute("userLabels", DemoUsers.LABELS);
        model.addAttribute("user", user == null ? null : Map.of("key", user.key(), "name", user.name(), "email", user.email(),
                "plan", user.plan(), "editor", user.editor()));
        model.addAttribute("demoUser", config.get("demoUser"));
        model.addAttribute("commercial", licence.startsWith("Commercial"));
        model.addAttribute("needsAi", licence.contains("needs an AI provider"));
        model.addAttribute("stepDocs", settings.docs() + "#" + step.get("anchor"));
        model.addAttribute("joinFailed", Boolean.TRUE.equals(request.getAttribute(SandboxFilter.JOIN_FAILED)));
        model.addAttribute("codeDefinitions", codePanel.definitions(step));
        model.addAttribute("codePanel", codePanel.regions(step));
        model.addAttribute("settings", Map.of("demoMode", settings.demoMode(), "validateResponses", settings.validateResponses(),
                "lintDefinitions", settings.lintDefinitions(), "aiApiKeySet", settings.aiApiKey() != null,
                "extractionConfigured", settings.extractionConfigured()));
        page.forEach((name, value) -> {
            if (!model.containsAttribute(name)) {   // /claim already put optionA's values in
                model.addAttribute(name, value);
            }
        });
        model.addAttribute("styles", frontend.stylesheets((String) page.get("theme")));
        return "examples/" + slug;
    }

    /** What the catalog, the PDF loading page and the example pages all read. */
    private void site(Model model) {
        model.addAttribute("title", TITLE);
        model.addAttribute("header", HEADER);
        model.addAttribute("build", settings.build());
        model.addAttribute("docs", settings.docs());
        model.addAttribute("github", settings.github());
        model.addAttribute("branch", manifest.branch());
        // https behind a TLS proxy: Tomcat's RemoteIpValve applies X-Forwarded-Proto to the request
        model.addAttribute("canonical", ServletUriComponentsBuilder.fromCurrentRequestUri().replaceQuery(null).build().toUriString());
        model.addAttribute("fonts", Frontend.FONTS);
    }

    private static User currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getPrincipal() instanceof User user ? user : null;
    }
}
