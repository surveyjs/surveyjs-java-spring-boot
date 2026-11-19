package io.surveyjs.tools;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import io.surveyjs.demo.CodePanel;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code ./mvnw -q test-compile exec:java@check [-Dexec.args=--complete]}: does this repo still match the integration
 * docs (server-integration-v4)?
 *
 * <p>The manifest's files and regions, the region rules (no nesting, short, no plumbing in server regions), shared/
 * against its SHA256SUMS, the SurveyJS pins against shared/surveyjs-version.json, the service revision against
 * docker-compose.yml, the pom's dependencies, and the README's docs links. With --complete (CI, the end of a run) all
 * 18 examples must be in the manifest. It throws on any finding, so Maven ends with exit code 1.
 */
public final class Check {

    static final List<String> STEPS = List.of("save-response", "load-definition-variables-data", "resume-progress", "store-files",
            "choices-from-web", "async-functions", "relational-storage", "fill-together", "dashboard", "creator-load-save",
            "creator-uploads", "ai-translation", "variable-presets", "edit-together", "validate-response", "lint-definition",
            "server-pdf", "extract-from-paper");
    static final List<String> REGION_SOURCES = List.of("src/main/java/io/surveyjs/examples", "src/main/java/io/surveyjs/relay", "shared/client");
    static final Set<String> RELAY_FILES = Set.of("src/main/java/io/surveyjs/examples/FillTogetherRelay.java",
            "src/main/java/io/surveyjs/examples/EditTogetherRelay.java");
    static final int MAX_REGION_LINES = 24;
    static final int MAX_RELAY_REGION_LINES = 60;
    static final String DOCS = "https://surveyjs.io/documentation/backend-integration-spring-boot";
    /** This platform's plumbing: a server region calls integration/ or storage/ for these instead of showing them. */
    static final List<String> PLUMBING = List.of("GeneratedKeyHolder", "KeyHolder", "writeValueAsString", "ResourceAccessException",
            "HttpTimeoutException", "ConnectException", "JdkClientHttpRequestFactory", "Instant.now", "truncatedTo", "SandboxContext");
    /** The only dependencies the pom may declare (README: Dependencies). */
    static final Set<String> DEPENDENCIES = Set.of("spring-boot-starter-webmvc", "spring-boot-starter-websocket",
            "spring-boot-starter-thymeleaf", "spring-boot-starter-jdbc", "spring-boot-starter-security", "thymeleaf-layout-dialect", "h2",
            "spring-boot-starter-webmvc-test", "spring-boot-starter-websocket-test", "spring-boot-starter-thymeleaf-test",
            "spring-boot-starter-jdbc-test", "spring-boot-starter-security-test");
    private static final Pattern OPENING = Pattern.compile("^\\s*//\\s*#region\\b");
    private static final Pattern CLOSING = Pattern.compile("^\\s*//\\s*#endregion\\b");

    private Check() {
    }

    public static void main(String[] args) throws IOException {
        List<String> errors = check(List.of(args).contains("--complete"));
        errors.forEach(error -> System.err.println("ERROR  " + error));
        if (!errors.isEmpty()) {
            throw new IllegalStateException(errors.size() + " finding(s): see above");
        }
        System.out.println("OK: " + manifest().get("steps").size() + " examples, regions, shared/, versions and pins are consistent.");
    }

    static JsonNode manifest() throws IOException {
        return JsonMapper.shared().readTree(read("surveyjs-integration.json"));
    }

    public static List<String> check(boolean complete) throws IOException {
        List<String> errors = new ArrayList<>();

        // ---- shared/: CONTRACT, UPSTREAM and the hashes (shared/ is a copy: fixes go to surveyjs-php-laravel)
        String contract = Files.isRegularFile(Path.of("shared/CONTRACT")) ? read("shared/CONTRACT").strip() : "";
        if (!contract.equals("server-integration-v4")) {
            errors.add("shared/CONTRACT must read server-integration-v4, found \"" + contract + "\"");
        }
        String upstream = Files.isRegularFile(Path.of("shared/UPSTREAM")) ? read("shared/UPSTREAM").strip() : "";
        if (!upstream.matches("surveyjs/surveyjs-php-laravel@[0-9a-f]{7,40}")) {
            errors.add("shared/UPSTREAM must name the copied commit (surveyjs/surveyjs-php-laravel@<sha>), found \"" + upstream + "\"");
        }
        Map<String, String> expected = new LinkedHashMap<>();
        for (String line : read("shared/SHA256SUMS").split("\n")) {
            if (!line.isBlank()) {
                expected.put(line.substring(66).strip(), line.substring(0, 64));
            }
        }
        Map<String, String> actual = new LinkedHashMap<>();
        for (String path : files("shared")) {
            if (!path.equals("shared/SHA256SUMS") && !path.equals("shared/UPSTREAM")) {
                actual.put(path.substring("shared/".length()), sha256(Files.readAllBytes(Path.of(path))));
            }
        }
        expected.forEach((path, digest) -> {
            if (!actual.containsKey(path)) {
                errors.add("shared/" + path + " is listed in shared/SHA256SUMS but missing");
            } else if (!actual.get(path).equals(digest)) {
                errors.add("shared/" + path + " differs from shared/SHA256SUMS: shared/ is a copy, fix it in surveyjs-php-laravel"
                        + " (on Windows, check that .gitattributes keeps LF line endings)");
            }
        });
        actual.keySet().stream().filter(path -> !expected.containsKey(path))
                .forEach(path -> errors.add("shared/" + path + " is not listed in shared/SHA256SUMS"));

        // ---- versions: the manifest, shared/surveyjs-version.json and package.json pin the same SurveyJS
        JsonNode manifest = manifest();
        JsonMapper json = JsonMapper.shared();
        String version = json.readTree(read("shared/surveyjs-version.json")).get("version").asString();
        if (!manifest.path("surveyjsVersion").asString("").equals(version)) {
            errors.add("surveyjsVersion in the manifest is " + manifest.path("surveyjsVersion") + ", shared/surveyjs-version.json says " + version);
        }
        String pinned = json.readTree(read("package.json")).path("devDependencies").path("survey-core").asString("");
        if (!pinned.equals(version)) {
            errors.add("package.json pins survey-core to " + pinned + ", expected exactly " + version + " (the lint spec imports it)");
        }
        if (Files.isRegularFile(Path.of("package-lock.json"))) {
            String locked = json.readTree(read("package-lock.json")).path("packages").path("node_modules/survey-core").path("version").asString("");
            if (!locked.equals(version)) {
                errors.add("package-lock.json has survey-core " + locked + ", expected " + version + ": run npm install");
            }
        }
        if (!manifest.path("serverSideWorkflows").asString("").equals("service")) {
            errors.add("serverSideWorkflows must be \"service\", found " + manifest.path("serverSideWorkflows"));
        }
        Matcher service = Pattern.compile("surveyjs-server\\.git#([0-9a-f]{40})").matcher(read("docker-compose.yml"));
        if (!service.find() || !manifest.path("surveyjsService").asString("").equals("surveyjs/surveyjs-server@" + service.group(1))) {
            errors.add("surveyjsService in the manifest differs from the surveyjs build context in docker-compose.yml");
        }

        // ---- the pom: only the dependencies README lists, none with its own version (Boot manages them)
        Matcher dependency = Pattern.compile("<dependency>\\s*<groupId>[^<]+</groupId>\\s*<artifactId>([^<]+)</artifactId>([\\s\\S]*?)</dependency>")
                .matcher(read("pom.xml"));
        while (dependency.find()) {
            if (!DEPENDENCIES.contains(dependency.group(1))) {
                errors.add("pom.xml declares " + dependency.group(1) + ", which isn't one of the dependencies README lists");
            }
            if (dependency.group(2).contains("<version>")) {
                errors.add("pom.xml pins " + dependency.group(1) + ": keep Spring Boot's managed version");
            }
        }

        // ---- the manifest's files
        for (JsonNode step : manifest.get("steps")) {
            String slug = step.get("slug").asString();
            step.get("files").properties().forEach(file -> {
                if (!Files.isRegularFile(Path.of(file.getValue().asString()))) {
                    errors.add(slug + ": " + file.getKey() + " file " + file.getValue().asString() + " is missing");
                }
            });
            String status = step.path("status").asString("");
            if (!status.equals("done") && !status.equals("not-in-repo")) {
                errors.add(slug + ": unknown status " + status);
            }
        }

        // ---- regions: markers balanced, each region once, short, listed, and in its example's client or server files
        Map<String, List<String>> found = new LinkedHashMap<>();
        for (String folder : REGION_SOURCES) {
            for (String path : files(folder)) {
                if (!path.endsWith(".java") && !path.endsWith(".js")) {
                    continue;
                }
                int openAt = 0;
                List<String> lines = Files.readAllLines(Path.of(path), StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    if (OPENING.matcher(lines.get(i)).find()) {
                        if (openAt > 0) {
                            errors.add(path + ":" + (i + 1) + ": a region opens inside another region");
                        }
                        openAt = i + 1;
                    } else if (CLOSING.matcher(lines.get(i)).find()) {
                        if (openAt == 0) {
                            errors.add(path + ":" + (i + 1) + ": endregion without region");
                        }
                        openAt = 0;
                    }
                }
                if (openAt > 0) {
                    errors.add(path + ":" + openAt + ": a region is never closed");
                }
                for (CodePanel.Region region : CodePanel.regions(path).values()) {
                    found.computeIfAbsent(region.id(), id -> new ArrayList<>()).add(path);
                    int count = region.end() - region.start() + 1;
                    int limit = RELAY_FILES.contains(path) ? MAX_RELAY_REGION_LINES : MAX_REGION_LINES;
                    if (region.end() > 0 && count > limit) {
                        errors.add("Region sjs:" + region.id() + " in " + path + " has " + count + " lines (at most " + limit + ")");
                    }
                    if (region.count() > 1) {
                        errors.add("Region sjs:" + region.id() + " appears " + region.count() + " times in " + path);
                    }
                    if (path.startsWith("src/")) {
                        for (String token : PLUMBING) {
                            if (region.code().contains(token)) {
                                errors.add("Region sjs:" + region.id() + " in " + path + " shows " + token + ": move it into integration/ or storage/");
                            }
                        }
                    }
                }
            }
        }
        Set<String> listed = new TreeSet<>();
        for (JsonNode step : manifest.get("steps")) {
            List<String> sources = new ArrayList<>();
            step.get("files").properties().forEach(file -> {
                if (file.getKey().equals("client") || file.getKey().startsWith("server")) {
                    sources.add(file.getValue().asString());
                }
            });
            for (JsonNode id : step.get("regions")) {
                listed.add(id.asString());
                List<String> where = found.getOrDefault(id.asString(), List.of());
                if (where.size() != 1) {
                    errors.add(step.get("slug").asString() + ": region sjs:" + id.asString() + " found " + where.size() + " times, expected exactly once");
                } else if (!sources.contains(where.get(0))) {
                    errors.add(step.get("slug").asString() + ": region sjs:" + id.asString() + " is in " + where.get(0) + ", not in the example's client or server file");
                }
            }
        }
        found.forEach((id, where) -> {
            if (!listed.contains(id)) {
                errors.add("Region sjs:" + id + " in " + where.get(0) + " is not listed in the manifest");
            }
        });

        // ---- README: the prose links the docs page exactly twice (the generated table always holds it)
        if (Files.isRegularFile(Path.of("README.md"))) {
            String prose = read("README.md").replaceAll("<!-- steps:start[\\s\\S]*?<!-- steps:end -->", "");
            Matcher link = Pattern.compile("]\\((https?://[^)\\s]+)\\)|<(https?://[^>\\s]+)>").matcher(prose);
            List<String> links = new ArrayList<>();
            while (link.find()) {
                String url = link.group(1) != null ? link.group(1) : link.group(2);
                if (url.startsWith(DOCS) || url.matches("https?://(www\\.)?surveyjs\\.io(/documentation)?/backend-integration.*")) {
                    links.add(url);
                }
            }
            if (links.size() != 2 || links.stream().anyMatch(url -> !url.equals(DOCS))) {
                errors.add("README.md must link to the docs exactly twice outside the example table, both to " + DOCS + "; found " + links.size());
            }
        } else {
            errors.add("README.md is missing");
        }

        if (complete) {
            Set<String> slugs = new TreeSet<>();
            manifest.get("steps").forEach(step -> slugs.add(step.get("slug").asString()));
            STEPS.stream().filter(slug -> !slugs.contains(slug))
                    .forEach(slug -> errors.add("Example " + slug + " is missing from surveyjs-integration.json"));
        }
        return errors;
    }

    static String read(String path) throws IOException {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8);
    }

    static List<String> files(String folder) throws IOException {
        try (Stream<Path> walk = Files.walk(Path.of(folder))) {
            return walk.filter(Files::isRegularFile).map(path -> path.toString().replace('\\', '/')).sorted().toList();
        }
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
