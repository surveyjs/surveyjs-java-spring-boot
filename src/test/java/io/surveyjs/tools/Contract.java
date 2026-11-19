package io.surveyjs.tools;

import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * {@code ./mvnw -q -DskipTests package exec:java@contract [-Dexec.args="--steps save-response,choices-from-web"]}, or
 * {@code -Dexec.args=--smoke}: runs the shared contract test (shared/contract-tests/run.mjs), or the smoke test
 * (shared/smoke/run.mjs), against a fresh DATA_DIR under target/contract/ and the packaged jar on a free port, then stops
 * the app. It prints the test's exit code and throws when it isn't 0, so Maven ends with exit code 1. Works on Windows
 * and Linux; needs Node 22+ on PATH for the test itself.
 *
 * <p>Passed through from the environment: SERVICE (off or on; off points the app at a port nothing listens on), AI (off
 * or stub), VALIDATE_RESPONSES, LINT_DEFINITIONS, DEMO_MODE, SURVEYJS_SERVICE_URL and the AI_* settings. With AI=stub,
 * start the stub first (docker-compose.ci.yml) and set AI_BASE_URL=http://127.0.0.1:3020/v1 AI_API_KEY=stub
 * AI_MODEL=stub. The relays run in the app, so RELAY_URL and EDIT_RELAY_URL are its ws:// origin.
 */
public final class Contract {

    /** Off means off: settings.env and .env must not switch translation or extraction on. A space overrides them with nothing. */
    private static final List<String> AI_SETTINGS = List.of("AI_API_KEY", "SURVEYJS_AI_PROVIDER", "SURVEYJS_AI_MODEL",
            "SURVEYJS_OLLAMA_BASE_URL", "OPENAI_API_KEY", "ANTHROPIC_API_KEY");
    private static final List<String> SWITCHES = List.of("VALIDATE_RESPONSES", "LINT_DEFINITIONS", "DEMO_MODE");
    private static final Path JAR = Path.of("target", "surveyjs-java-spring-boot.jar");

    private Contract() {
    }

    public static void main(String[] args) throws Exception {
        String joined = String.join(" ", args);
        boolean smoke = joined.contains("--smoke");
        Matcher steps = Pattern.compile("--steps[= ](\\S+)").matcher(joined);
        if (!Files.isRegularFile(JAR)) {
            throw new IllegalStateException(JAR + " is missing: run ./mvnw -q -DskipTests package first");
        }

        Path dataDir = Path.of("target", "contract", "run-" + Instant.now().toEpochMilli()).toAbsolutePath();
        Files.createDirectories(dataDir);
        int port = freePort();
        String origin = "http://127.0.0.1:" + port;

        ProcessBuilder app = new ProcessBuilder(javaCommand(), "-jar", JAR.toString());
        Map<String, String> env = app.environment();
        env.put("DATA_DIR", dataDir.toString());
        env.put("APP_PORT", String.valueOf(port));
        env.put("APP_HOST", "127.0.0.1");
        env.put("SERVICE", orDefault(System.getenv("SERVICE"), "off"));
        env.put("AI", orDefault(System.getenv("AI"), "off"));
        env.put("SURVEYJS_BUILD", orDefault(System.getenv("SURVEYJS_BUILD"), "contract"));
        for (String name : SWITCHES) {   // what the test expects is what the app runs with, whatever settings.env and .env say
            env.put(name, orDefault(System.getenv(name), "false"));
        }
        if (!env.get("AI").equals("stub")) {
            AI_SETTINGS.forEach(name -> env.put(name, " "));
        }
        if (env.get("SERVICE").equals("off")) {   // off means not running, also when a service answers on 3010
            env.put("SURVEYJS_SERVICE_URL", "http://127.0.0.1:" + freePort());
        }
        File log = dataDir.resolveSibling(dataDir.getFileName() + ".log").toFile();
        app.redirectErrorStream(true).redirectOutput(log);

        Process running = app.start();
        int code = 1;
        try {
            waitFor(origin + "/up", running);
            System.out.println("Started the app on port " + port + " (DATA_DIR " + dataDir + ")");
            List<String> test = new ArrayList<>(List.of(node(), smoke ? "shared/smoke/run.mjs" : "shared/contract-tests/run.mjs"));
            if (steps.find()) {
                test.add("--steps=" + steps.group(1));
            }
            ProcessBuilder node = new ProcessBuilder(test).inheritIO();
            node.environment().putAll(env);
            node.environment().put("BASE_URL", origin);
            node.environment().put("RELAY_URL", "ws://127.0.0.1:" + port);
            node.environment().put("EDIT_RELAY_URL", "ws://127.0.0.1:" + port);
            code = node.start().waitFor();
        } finally {
            running.destroy();
            if (!running.waitFor(java.util.concurrent.TimeUnit.SECONDS.toMillis(15), java.util.concurrent.TimeUnit.MILLISECONDS)) {
                running.destroyForcibly().waitFor();
            }
            if (code != 0) {
                String output = Files.readString(log.toPath());
                System.out.println("--- the app's output\n" + output.substring(Math.max(0, output.length() - 6000)));
            }
            try {
                delete(dataDir);
                Files.deleteIfExists(log.toPath());
            } catch (IOException e) {   // Windows may hold the stopped app's files a moment longer: target/ is disposable
                System.out.println("Left " + dataDir + " behind: " + e.getMessage());
            }
        }
        System.out.println("Test exit code: " + code);
        if (code != 0) {
            throw new IllegalStateException("The " + (smoke ? "smoke" : "contract") + " test failed with exit code " + code);
        }
    }

    /** The running JVM's own java, so the app runs on the JDK Maven runs on. */
    private static String javaCommand() {
        String exe = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", exe).toString();
    }

    private static String node() {
        String path = System.getenv("PATH");
        String[] names = System.getProperty("os.name").startsWith("Windows") ? new String[] {"node.exe"} : new String[] {"node"};
        for (String dir : path == null ? new String[0] : path.split(File.pathSeparator)) {
            for (String name : names) {
                if (Files.isExecutable(Path.of(dir, name))) {
                    return Path.of(dir, name).toString();
                }
            }
        }
        throw new IllegalStateException("Node.js 22 or newer is needed on PATH to run the contract test (shared/contract-tests/run.mjs)");
    }

    private static void waitFor(String url, Process app) throws InterruptedException {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
        Instant deadline = Instant.now().plusSeconds(60);
        while (Instant.now().isBefore(deadline)) {
            if (!app.isAlive()) {
                throw new IllegalStateException("The app stopped with exit code " + app.exitValue());
            }
            try {
                if (http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(1)).build(),
                        HttpResponse.BodyHandlers.discarding()).statusCode() == 200) {
                    return;
                }
            } catch (IOException notYet) {
                Thread.sleep(200);
            }
        }
        throw new IllegalStateException("The app did not answer " + url + " within 60 s");
    }

    private static int freePort() throws IOException {
        try (ServerSocket probe = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            return probe.getLocalPort();
        }
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static void delete(Path folder) throws IOException {
        if (Files.exists(folder)) {
            try (Stream<Path> files = Files.walk(folder)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(file);
                }
            }
        }
    }
}
