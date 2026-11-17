package io.surveyjs.demo;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Demo only: surveyjs-integration.json, the list of examples the catalog, the example pages and the docs read.
 * Read once at start. Steps are maps with the manifest's keys, as the templates read them.
 */
@Component
public class Manifest {

    private final Map<String, Object> manifest;
    private final List<Map<String, Object>> steps;

    @SuppressWarnings("unchecked")
    public Manifest(JsonMapper json) {
        try {
            manifest = json.readValue(Files.readString(Path.of("surveyjs-integration.json")), new TypeReference<>() { });
        } catch (IOException e) {
            throw new UncheckedIOException("Can't read surveyjs-integration.json", e);
        }
        steps = (List<Map<String, Object>>) manifest.get("steps");
    }

    public List<Map<String, Object>> steps() {
        return steps;
    }

    public Optional<Map<String, Object>> step(String slug) {
        return steps.stream().filter(step -> slug.equals(step.get("slug"))).findFirst();
    }

    public String branch() {
        return (String) manifest.get("branch");
    }

    public String surveyjsVersion() {
        return (String) manifest.get("surveyjsVersion");
    }

    /** A step's files, by role: definition, client, server, page, … */
    @SuppressWarnings("unchecked")
    public static Map<String, String> files(Map<String, Object> step) {
        return (Map<String, String>) step.get("files");
    }

    @SuppressWarnings("unchecked")
    public static List<String> regions(Map<String, Object> step) {
        return (List<String>) step.get("regions");
    }
}
