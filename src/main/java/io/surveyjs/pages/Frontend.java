package io.surveyjs.pages;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Demo only: the import map and the stylesheets every example page loads, built once at start.
 *
 * <p>The shared ES modules (shared/client/*.js, shared/ui/*.js) import bare specifiers ("survey-core",
 * "lodash/debounce"). No Maven artifact carries SurveyJS's 3.x browser builds (WebJars stop at survey-core 1.12), so
 * the import map points every specifier at unpkg: the SurveyJS packages at the version in
 * shared/surveyjs-version.json, the others at the pins below. One place pins every page. To self-host them, copy each
 * package's fesm/ files into src/main/resources/static/vendor/ and point {@link #UNPKG} there (README: Front end).
 */
@Component
public class Frontend {

    static final String UNPKG = "https://unpkg.com";

    /** Every SurveyJS specifier the shared modules and the SurveyJS builds import: package and its browser ES module,
     *  each checked in https://unpkg.com/&lt;package&gt;@&lt;version&gt;/?meta. */
    private static final Map<String, String[]> SURVEYJS = linked(
            "survey-core", new String[] {"survey-core", "fesm/survey-core.mjs"},
            "survey-core/collaboration", new String[] {"survey-core", "fesm/collaboration/index.mjs"},
            "survey-core/i18n", new String[] {"survey-core", "fesm/i18n/index.mjs"},
            "survey-js-ui", new String[] {"survey-js-ui", "fesm/survey-js-ui.mjs"},
            "survey-creator-core", new String[] {"survey-creator-core", "fesm/survey-creator-core.mjs"},
            "survey-creator-core/collaboration", new String[] {"survey-creator-core", "fesm/collaboration.mjs"},
            "survey-creator-js", new String[] {"survey-creator-js", "fesm/survey-creator-js.mjs"},
            "survey-analytics", new String[] {"survey-analytics", "fesm/survey.analytics.mjs"});

    /** highlight.js's own es/ files re-export its CommonJS lib/ (they are for bundlers), so a browser gets no default
     *  export: @highlightjs/cdn-assets is the same release built as browser ES modules. */
    static final String HIGHLIGHT = UNPKG + "/@highlightjs/cdn-assets@11.12.0";

    /** chart.js and @kurkle/color are the versions survey-analytics allows (^4.5.1), as in the reference's lock file. */
    private static final Map<String, String> THIRD_PARTY = Map.of(
            "chart.js", UNPKG + "/chart.js@4.5.1/dist/chart.js",
            "@kurkle/color", UNPKG + "/@kurkle/color@0.3.4/dist/color.esm.js",
            "lodash/debounce", UNPKG + "/lodash-es@4.18.1/debounce.js",
            "highlight.js/lib/core", HIGHLIGHT + "/es/core.min.js");

    private static final Pattern LANGUAGE = Pattern.compile("from\\s+['\"]highlight\\.js/lib/languages/([a-z0-9-]+)['\"]");

    public static final String HIGHLIGHT_THEME = HIGHLIGHT + "/styles/github-dark.min.css";

    public static final List<String> FONTS = List.of("Regular", "Medium", "Semibold", "Bold").stream()
            .map(weight -> "/shared/ui/fonts/lato/LatoLatin-" + weight + ".woff2").toList();

    private final String version;
    private final Map<String, Object> importMap;
    private final Map<String, List<String>> stylesheets;

    public Frontend(JsonMapper json) {
        try {
            version = json.readTree(Files.readString(Path.of("shared", "surveyjs-version.json"))).get("version").asString();
            importMap = buildImportMap(Files.readString(Path.of("shared", "ui", "code-highlight.js")));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        String core = surveyjs("survey-core", "survey-core.min.css");
        String adapter = surveyjs("survey-core", "themes/adapters/tailwind4.min.css");
        stylesheets = Map.of(
                "survey", List.of(core, surveyjs("survey-core", "collaboration.min.css"), adapter, "/shared/ui/survey-overrides.css"),
                "creator", List.of(core, surveyjs("survey-creator-core", "survey-creator-core.min.css"),
                        surveyjs("survey-creator-core", "collaboration.min.css"), adapter),
                "dashboard", List.of(core, surveyjs("survey-analytics", "survey.analytics.min.css"), adapter));
    }

    /** The SurveyJS version every page pins. */
    public String version() {
        return version;
    }

    /** {"imports": {specifier: url}}: written into each example page as {@code <script type="importmap">}. */
    public Map<String, Object> importMap() {
        return importMap;
    }

    /** The SurveyJS styles of a page theme (survey, creator or dashboard), in the order the pages need them. */
    public List<String> stylesheets(String theme) {
        return stylesheets.get(theme);
    }

    private Map<String, Object> buildImportMap(String codeHighlight) {
        Map<String, String> imports = new LinkedHashMap<>();
        SURVEYJS.forEach((specifier, file) -> imports.put(specifier, surveyjs(file[0], file[1])));
        THIRD_PARTY.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> imports.put(e.getKey(), e.getValue()));
        // One entry per language shared/ui/code-highlight.js imports, so a language added there can't go unmapped
        Matcher language = LANGUAGE.matcher(codeHighlight);
        while (language.find()) {
            imports.put("highlight.js/lib/languages/" + language.group(1), HIGHLIGHT + "/es/languages/" + language.group(1) + ".min.js");
        }
        return Map.of("imports", imports);
    }

    private String surveyjs(String pkg, String file) {
        return UNPKG + "/" + pkg + "@" + version + "/" + file;
    }

    private static Map<String, String[]> linked(Object... pairs) {
        Map<String, String[]> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], (String[]) pairs[i + 1]);
        }
        return map;
    }
}
