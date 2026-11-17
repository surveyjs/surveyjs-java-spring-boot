package io.surveyjs.demo;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.surveyjs.config.SurveyJsProperties;
import org.springframework.stereotype.Component;

/**
 * Demo only: the Code tab. It shows the files that just ran: each JSON definition, then each region the manifest lists
 * for the example, dedented, with a link to its lines on GitHub. Java and JavaScript mark regions the same way,
 * {@code // #region sjs:<id>} … {@code // #endregion}. The check tool reads regions with {@link #regions(String)}.
 */
@Component
public class CodePanel {

    private static final Pattern OPENING = Pattern.compile("^\\s*//\\s*#region\\s+sjs:(\\S+)");
    private static final Pattern CLOSING = Pattern.compile("^\\s*//\\s*#endregion\\b");
    private static final Map<String, String> LANGUAGES = Map.of(".java", "java", ".js", "javascript", ".json", "json");

    /** A region: its first and last line inside the markers (1-based), its dedented code, how often it appears. */
    public record Region(String id, int start, int end, String code, int count, boolean nested) {
    }

    private final SurveyJsProperties settings;
    private final Manifest manifest;

    public CodePanel(SurveyJsProperties settings, Manifest manifest) {
        this.settings = settings;
        this.manifest = manifest;
    }

    /** Every region in a file of the repository, by id, in order. */
    public static Map<String, Region> regions(String path) {
        if (!path.endsWith(".java") && !path.endsWith(".js")) {
            return Map.of();
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(Path.of(path));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Map<String, Region> regions = new LinkedHashMap<>();
        String openId = null;
        int openLine = 0;
        boolean nested = false;
        for (int i = 0; i < lines.size(); i++) {
            Matcher opening = OPENING.matcher(lines.get(i));
            if (opening.find()) {
                nested = openId != null;
                openId = opening.group(1);
                openLine = i + 1;
            } else if (openId != null && CLOSING.matcher(lines.get(i)).find()) {
                Region existing = regions.get(openId);
                regions.put(openId, existing != null
                        ? new Region(openId, existing.start(), existing.end(), existing.code(), existing.count() + 1, existing.nested() || nested)
                        : new Region(openId, openLine + 1, i, dedent(lines.subList(openLine, i)), 1, nested));
                openId = null;
                nested = false;
            }
        }
        if (openId != null) {
            regions.putIfAbsent(openId, new Region(openId, openLine + 1, -1, "", 1, nested));   // unclosed: end = -1
        }
        return regions;
    }

    static String dedent(List<String> lines) {
        int indent = lines.stream().filter(line -> !line.isBlank())
                .mapToInt(line -> line.length() - line.stripLeading().length()).min().orElse(0);
        List<String> out = new ArrayList<>();
        for (String line : lines) {
            out.add(line.isBlank() ? "" : line.substring(indent));
        }
        return String.join("\n", out);
    }

    /** The JSON definitions, shown collapsed above the regions: maps with path, code and url. */
    public List<Map<String, Object>> definitions(Map<String, Object> step) {
        List<Map<String, Object>> definitions = new ArrayList<>();
        Manifest.files(step).forEach((role, path) -> {
            if (role.startsWith("definition")) {
                try {
                    definitions.add(Map.of("path", path, "code", Files.readString(Path.of(path)).strip(), "url", github(path)));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        });
        return definitions;
    }

    /** The regions the manifest lists, from the client file and every server* file, in manifest order. */
    public List<Map<String, Object>> regions(Map<String, Object> step) {
        List<String> listed = Manifest.regions(step);
        List<Map<String, Object>> panel = new ArrayList<>();
        Manifest.files(step).forEach((role, path) -> {
            if (!role.equals("client") && !role.startsWith("server")) {
                return;
            }
            regions(path).forEach((id, region) -> {
                if (listed.contains(id)) {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("id", id);
                    entry.put("role", role.equals("client") ? "client" : "server");
                    entry.put("path", path);
                    entry.put("language", LANGUAGES.getOrDefault(path.substring(path.lastIndexOf('.')), "plaintext"));
                    entry.put("code", region.code());
                    entry.put("url", github(path) + "#L" + region.start() + "-L" + region.end());
                    panel.add(entry);
                }
            });
        });
        return panel;
    }

    private String github(String path) {
        return settings.github() + "/blob/" + manifest.branch() + "/" + path;
    }
}
