package io.surveyjs.config;

import java.nio.file.Path;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Every setting the app reads, bound once from the {@code surveyjs.*} keys that application.properties maps from
 * the cross-platform environment variable names (APP_PORT, SURVEYJS_SERVICE_URL, DEMO_MODE, …). Inject it; no
 * other class reads the environment.
 *
 * <p>Switches accept {@code 1|true|on|yes} (Spring's conversion). A blank text value means unset: the contract
 * runner sets a key to a single space to override a developer's settings.env or .env with nothing.
 */
@ConfigurationProperties("surveyjs")
public record SurveyJsProperties(
        // Run Server-Side Workflows: SurveyJS Server (github.com/surveyjs/surveyjs-server), next to this app
        String serviceUrl,
        Boolean validateResponses,   // ValidateResponseController replaces SaveResponseController
        Boolean lintDefinitions,     // LintDefinitionController replaces CreatorLoadSaveController
        // Demo only: a private H2 database and uploads folder per visitor (README: How data works in the demo)
        Boolean demoMode,
        // The relays run in this app, on its port. Set these to a proxy's origin (wss://example.com) when it
        // serves /ws/ on another host; unset, the pages use their own origin.
        String relayUrl,
        String editRelayUrl,
        // Shown in the browser: host.js calls setLicenseKey() only when it is set
        String licenseKey,
        // Translate with AI: any OpenAI-compatible /chat/completions endpoint
        String aiBaseUrl,
        String aiApiKey,
        String aiModel,
        // Extract Responses from Documents: Docker Compose passes these to the SurveyJS service under its own
        // names (AI_PROVIDER, AI_MODEL, OLLAMA_BASE_URL). The app reads them only for the page's banner.
        String aiProvider,
        String aiExtractionModel,
        String ollamaBaseUrl,
        String openaiApiKey,
        String anthropicApiKey,
        // Which checkout answered, <branch>@<commit>: StartupGuard sets it from git, deploys set it themselves
        String build,
        // The database, uploads, visitor sandboxes and relay snapshots; relative to the repository root
        Path dataDir,
        // Where the pages link: the docs (each example adds #<anchor>) and the repository (View on GitHub)
        String docs,
        String github,
        int appPort) {

    public SurveyJsProperties {
        serviceUrl = withoutTrailingSlash(orDefault(serviceUrl, "http://localhost:3010"));
        validateResponses = Boolean.TRUE.equals(validateResponses);
        lintDefinitions = Boolean.TRUE.equals(lintDefinitions);
        demoMode = Boolean.TRUE.equals(demoMode);
        relayUrl = blankToNull(relayUrl);
        editRelayUrl = blankToNull(editRelayUrl);
        licenseKey = blankToNull(licenseKey);
        aiBaseUrl = withoutTrailingSlash(orDefault(aiBaseUrl, "https://api.openai.com/v1"));
        aiApiKey = blankToNull(aiApiKey);
        aiModel = orDefault(aiModel, "gpt-4o-mini");
        aiProvider = blankToNull(aiProvider);
        aiExtractionModel = blankToNull(aiExtractionModel);
        ollamaBaseUrl = blankToNull(ollamaBaseUrl);
        openaiApiKey = blankToNull(openaiApiKey);
        anthropicApiKey = blankToNull(anthropicApiKey);
        build = blankToNull(build);
        dataDir = Path.of("").toAbsolutePath().resolve(dataDir == null ? Path.of("storage") : dataDir).normalize();
        docs = orDefault(docs, "https://surveyjs.io/documentation/backend-integration-spring-boot");
        github = withoutTrailingSlash(orDefault(github, "https://github.com/surveyjs/surveyjs-java-spring-boot"));
    }

    /** Whether the extract-from-paper page may say extraction is configured (the service has a provider). */
    public boolean extractionConfigured() {
        return aiProvider != null || openaiApiKey != null || anthropicApiKey != null;
    }

    /** The H2 database, uploads folder and relay snapshots under DATA_DIR. */
    public Path databaseDir() {
        return dataDir.resolve("database");
    }

    public Path uploadsDir() {
        return dataDir.resolve("uploads");
    }

    public Path sandboxDir() {
        return dataDir.resolve("sandbox");
    }

    public Path relayDir() {
        return dataDir.resolve("relay");
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static String orDefault(String value, String fallback) {
        String text = blankToNull(value);
        return text == null ? fallback : text;
    }

    private static String withoutTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
