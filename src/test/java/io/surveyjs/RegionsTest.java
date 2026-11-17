package io.surveyjs;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import io.surveyjs.demo.CodePanel;
import io.surveyjs.demo.CodePanel.Region;
import io.surveyjs.tools.Check;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The comments on the pitfalls that corrupt data or leak it are inside the regions the docs show, and every region the
 * manifest lists exists in the example's files.
 */
class RegionsTest {

    static final String EXAMPLES = "src/main/java/io/surveyjs/examples/";

    static String region(String file, String id) {
        Region found = CodePanel.regions(EXAMPLES + file).get(id);
        assertThat(found).as(file + " has sjs:" + id).isNotNull();
        assertThat(found.end()).as("sjs:" + id + " is closed").isPositive();
        return found.code();
    }

    @Test
    void thePitfallCommentsAreInsideTheRegions() {
        Object[][] pitfalls = {
            {"SaveResponseController.java", "save-response.server", List.of("JsonNode", "drop unknown keys", "{} stays {}", "1.0 stays 1.0")},
            {"ResumeProgressController.java", "resume-progress.server", List.of("JsonNode", "{} stays {}")},
            {"RelationalStorageController.java", "relational-storage.server", List.of("JsonNode", "valueName", "@Transactional")},
            {"CreatorLoadSaveController.java", "creator-load-save.server", List.of("JsonNode", "{} stays {}", "hasAuthority('edit-forms')")},
            {"VariablePresetsController.java", "variable-presets.server", List.of("JsonNode", "{} stays {}", "hasAuthority('edit-forms')")},
            {"ValidateResponseController.java", "validate-response.server", List.of("JsonNode", "fail closed", "exactly {}")},
            {"LintDefinitionController.java", "lint-definition.server", List.of("keeps {} as {}", "fail closed")},
            {"StoreFilesController.java", "store-files.server", List.of("files[]", "never the client's Content-Type", "never by the client",
                    "the whole batch")},
            {"StoreFilesController.java", "store-files.download", List.of("404, not 403", "nosniff", "sandbox", "check access first")},
            {"ChoicesFromWebController.java", "choices-from-web.server", List.of("the browser can't call a service without CORS")},
            {"DashboardController.java", "dashboard.server", List.of("as stored", "ISO 8601 UTC strings compare as dates")},
            {"ExtractFromPaperController.java", "extract-from-paper.server", List.of("JSON with base64", "logged by status only",
                    "never the client's Content-Type")},
            {"ServerPdfController.java", "server-pdf.server", List.of("a 200 alone isn't a PDF", "schema version")},
            {"LoadDefinitionVariablesDataController.java", "load-definition-variables-data.server", List.of("th:inline", "only the signed-in customer's own claims")},
            {"FillTogetherRelay.java", "fill-together.server", List.of("real session check", "refused handshake")},
            {"EditTogetherRelay.java", "edit-together.server", List.of("real session check", "editing together is for editors")},
        };
        for (Object[] pitfall : pitfalls) {
            String code = region((String) pitfall[0], (String) pitfall[1]);
            @SuppressWarnings("unchecked")
            List<String> comments = (List<String>) pitfall[2];
            for (String comment : comments) {
                assertThat(code).as("sjs:" + pitfall[1] + " explains: " + comment).contains(comment);
            }
        }
    }

    @Test
    void everyRegionTheManifestListsIsInTheExamplesFiles() {
        JsonNode manifest = TestSupport.json(PagesTest.read("surveyjs-integration.json"));
        for (JsonNode step : manifest.get("steps")) {
            JsonNode files = step.get("files");
            for (JsonNode id : step.get("regions")) {
                boolean found = files.propertyNames().stream()
                        .filter(role -> role.equals("client") || role.startsWith("server"))
                        .map(role -> CodePanel.regions(files.get(role).asString()))
                        .anyMatch(regions -> regions.containsKey(id.asString()));
                assertThat(found).as(step.get("slug").asString() + ": sjs:" + id.asString()).isTrue();
            }
            for (String role : files.propertyNames()) {
                Map<String, Region> regions = CodePanel.regions(files.get(role).asString());
                regions.values().forEach(region -> {
                    assertThat(region.count()).as(files.get(role).asString() + " sjs:" + region.id() + " appears once").isEqualTo(1);
                    assertThat(region.nested()).as("sjs:" + region.id() + " is not nested").isFalse();
                });
            }
        }
    }

    @Test
    void theCheckToolFindsNothingButTheMissingReadme() throws IOException {
        List<String> errors = Check.check(true);
        assertThat(errors.stream().filter(error -> !error.contains("README")).toList()).isEmpty();
    }
}
