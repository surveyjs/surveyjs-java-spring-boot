package io.surveyjs;

import java.io.File;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A busy APP_PORT stops the app before Spring creates anything: Boot's "APPLICATION FAILED TO START" block with the
 * meta prompt's message, exit code 1, and no database file in DATA_DIR. The app runs as {@code java io.surveyjs.Application}
 * in a child JVM on the test classpath, from the repository root, exactly as {@code main} starts it.
 */
class StartupTest {

    @Test
    void aBusyPortFailsWithTheMessageBeforeAnythingIsCreated() throws Exception {
        Path dataDir = TestSupport.newDataDir();
        try (ServerSocket busy = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            int port = busy.getLocalPort();
            Process app = start("--server.port=" + port, "--surveyjs.data-dir=" + dataDir);
            String output = new String(app.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(app.waitFor(60, TimeUnit.SECONDS)).isTrue();
            assertThat(app.exitValue()).as(output).isEqualTo(1);
            assertThat(output).contains("APPLICATION FAILED TO START");
            assertThat(output).contains("Port " + port + " is already in use. Another copy of this app (another checkout or branch?) may be running."
                    + " Stop it, or set APP_PORT.");
            assertThat(output).doesNotContain("Tomcat initialized").doesNotContain("HikariDataSource").doesNotContain("Seeded");
        }
        assertThat(Files.exists(dataDir.resolve("database"))).isFalse();
        try (Stream<Path> files = Files.walk(dataDir)) {
            assertThat(files.filter(Files::isRegularFile).toList()).isEmpty();
        }
    }

    @Test
    void theEnvironmentNameOfThePortCountsToo() throws Exception {
        Path dataDir = TestSupport.newDataDir();
        try (ServerSocket busy = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            int port = busy.getLocalPort();
            Process app = start("--APP_PORT=" + port, "--surveyjs.data-dir=" + dataDir);
            String output = new String(app.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(app.waitFor(60, TimeUnit.SECONDS)).isTrue();
            assertThat(app.exitValue()).as(output).isEqualTo(1);
            assertThat(output).contains("Port " + port + " is already in use.");
        }
        assertThat(Files.exists(dataDir.resolve("database"))).isFalse();
    }

    /** {@code java @argfile io.surveyjs.Application <args>}: the classpath goes in an argument file (Windows limits command lines). */
    static Process start(String... args) throws Exception {
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Path argFile = Files.createTempFile(TestSupport.newDataDir(), "classpath", ".args");
        Files.writeString(argFile, "-cp \"" + classpath.replace('\\', '/') + "\"\n");
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        List<String> command = new java.util.ArrayList<>(List.of(java, "@" + argFile, "io.surveyjs.Application"));
        command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command).directory(new File(System.getProperty("user.dir"))).redirectErrorStream(true);
        // The child must not pick up switches a developer has set
        for (String name : List.of("APP_PORT", "DATA_DIR", "VALIDATE_RESPONSES", "LINT_DEFINITIONS", "DEMO_MODE")) {
            builder.environment().remove(name);
        }
        return builder.start();
    }
}
