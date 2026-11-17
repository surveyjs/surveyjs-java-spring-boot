package io.surveyjs;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.event.SpringApplicationEvent;
import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.context.ApplicationListener;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Runs before Spring creates any bean, database or web server ({@link ApplicationEnvironmentPreparedEvent}), with the
 * environment already read: .env, environment variables and {@code --server.port} all count.
 *
 * <ul>
 *   <li>It refuses to start from anywhere but the repository root, where {@code shared/} lives.</li>
 *   <li>It refuses a port something already answers on. On Windows, Tomcat's own refusal comes only after the
 *       context has started (and created and seeded the database), and a second copy in the same folder fails
 *       earlier still, on H2's file lock.</li>
 *   <li>Unless {@code SURVEYJS_BUILD} is set, it asks git which branch and commit this checkout is on, once, and
 *       logs "Serving &lt;folder&gt; (&lt;build&gt;) on http://127.0.0.1:&lt;port&gt;" once the app is ready.</li>
 * </ul>
 * Registered by {@link Application#main}; {@link PortBusyAnalyzer} turns the refusal into Boot's
 * "APPLICATION FAILED TO START" message (META-INF/spring.factories).
 */
public class StartupGuard implements ApplicationListener<SpringApplicationEvent>, Ordered {

    private static final Log log = LogFactory.getLog(StartupGuard.class);

    private int port;
    private String build;

    @Override
    public void onApplicationEvent(SpringApplicationEvent event) {
        if (event instanceof ApplicationEnvironmentPreparedEvent prepared) {
            check(prepared.getEnvironment());
        } else if (event instanceof ApplicationReadyEvent) {
            Path folder = Path.of("").toAbsolutePath();
            log.info("Serving " + folder + (build == null ? "" : " (" + build + ")") + " on http://127.0.0.1:" + port);
        }
    }

    /** After Boot's logging listener, so the messages use the app's logging. */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 30;
    }

    private void check(ConfigurableEnvironment environment) {
        if (!Files.isRegularFile(Path.of("shared", "CONTRACT"))) {
            throw new IllegalStateException("Run the app from the repository root (shared/CONTRACT not found)");
        }
        port = environment.getProperty("server.port", Integer.class, 8080);
        if (port > 0 && answers(port)) {
            throw new PortBusyException(port);
        }
        build = environment.getProperty("surveyjs.build");
        if (build == null || build.isBlank()) {
            build = gitBuild();
            if (build != null) {
                environment.getPropertySources().addFirst(new MapPropertySource("surveyjsBuild", Map.of("surveyjs.build", build)));
            }
        }
    }

    /** Whether anything accepts a connection on 127.0.0.1:port within 500 ms. */
    static boolean answers(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 500);
            return true;
        } catch (IOException refused) {
            return false;
        }
    }

    /** {@code <branch>@<short commit>}, or null without git or a commit. */
    static String gitBuild() {
        String branch = git("rev-parse", "--abbrev-ref", "HEAD");
        String commit = git("rev-parse", "--short", "HEAD");
        return branch == null || commit == null ? null : branch + "@" + commit;
    }

    private static String git(String... args) {
        String[] command = new String[args.length + 1];
        command[0] = "git";
        System.arraycopy(args, 0, command, 1, args.length);
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return null;
            }
            String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            return process.exitValue() == 0 && !out.isEmpty() ? out : null;
        } catch (IOException git) {   // no git on PATH: print nothing extra
            return null;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /** Thrown when something already answers on the app's port. */
    public static class PortBusyException extends RuntimeException {

        private final int port;

        PortBusyException(int port) {
            super("Port " + port + " is already in use");
            this.port = port;
        }

        public int port() {
            return port;
        }
    }

    /** Prints the refusal in Boot's "APPLICATION FAILED TO START" block, without a stack trace. */
    public static class PortBusyAnalyzer extends AbstractFailureAnalyzer<PortBusyException> {

        @Override
        protected FailureAnalysis analyze(Throwable rootFailure, PortBusyException cause) {
            return new FailureAnalysis(
                    "Port " + cause.port() + " is already in use. Another copy of this app (another checkout or branch?) may be running. Stop it, or set APP_PORT.",
                    "Stop the other copy (README: Stop everything), or start this one on another port, for example with APP_PORT=8001.", cause);
        }
    }
}
