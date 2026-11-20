package io.surveyjs.tools;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code ./mvnw -q test-compile exec:java@readme}: regenerates the README's example table (between the steps markers)
 * from surveyjs-integration.json.
 */
public final class Readme {

    private static final String DOCS = "https://surveyjs.io/documentation/backend-integration-java-spring-boot";
    private static final Pattern MARKERS = Pattern.compile("(<!-- steps:start[^>]*-->)[\\s\\S]*?(<!-- steps:end -->)");

    private Readme() {
    }

    public static void main(String[] args) throws Exception {
        JsonNode manifest = JsonMapper.shared().readTree(Files.readString(Path.of("surveyjs-integration.json")));
        Path path = Path.of("README.md");
        String readme = Files.readString(path, StandardCharsets.UTF_8);

        List<String> rows = new ArrayList<>(List.of("| Example | Run | Server | Client | Page | Licence |", "|---|---|---|---|---|---|"));
        for (JsonNode step : manifest.get("steps")) {
            String page = step.get("page").asString();
            String run = manifest.hasNonNull("live") ? "[" + page + "](" + manifest.get("live").asString() + page + ")" : "`" + page + "`";
            rows.add("| [" + step.get("title").asString() + "](" + DOCS + "#" + step.get("anchor").asString() + ") | " + run
                    + " | " + file(step, "server") + " | " + file(step, "client") + " | " + file(step, "page") + " | "
                    + step.get("licence").asString() + " |");
        }

        Matcher markers = MARKERS.matcher(readme);
        if (!markers.find()) {
            throw new IllegalStateException("README.md needs <!-- steps:start --> and <!-- steps:end --> markers.");
        }
        String table = markers.group(1) + "\n" + String.join("\n", rows) + "\n" + markers.group(2);
        Files.writeString(path, readme.substring(0, markers.start()) + table + readme.substring(markers.end()), StandardCharsets.UTF_8);
        System.out.println("Updated the example table in README.md (" + manifest.get("steps").size() + " examples).");
    }

    private static String file(JsonNode step, String role) {
        JsonNode path = step.get("files").get(role);
        return path == null ? "—" : "[`" + path.asString() + "`](" + path.asString() + ")";
    }
}
