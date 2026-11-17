package io.surveyjs;

import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The app starts with no WARN or ERROR line in its log, in normal and in demo mode: on a fresh DATA_DIR (it creates and
 * seeds the database) and on the same one again (it opens the existing database).
 */
@ExtendWith(OutputCaptureExtension.class)
class QuietStartupTest {

    static final Pattern NOISE = Pattern.compile("^\\S+\\s+(WARN|ERROR)\\b|^WARNING:|^SLF4J", Pattern.MULTILINE);

    @ParameterizedTest(name = "demo mode {0}")
    @ValueSource(booleans = {false, true})
    void theContextStartsQuietly(boolean demoMode, CapturedOutput output) throws Exception {
        String dataDir = TestSupport.newDataDir().toString();
        for (int run = 0; run < 2; run++) {
            int before = output.getAll().length();
            try (ConfigurableApplicationContext context = new SpringApplicationBuilder(Application.class).web(WebApplicationType.SERVLET)
                    .run("--server.port=0", "--surveyjs.data-dir=" + dataDir, "--DEMO_MODE=" + demoMode)) {
                assertThat(context.isRunning()).isTrue();
            }
            String log = output.getAll().substring(before);
            assertThat(log).contains("Tomcat started on port");
            assertThat(log.contains("Created and seeded the database")).as("seeded on the first run only").isEqualTo(run == 0);
            List<String> noise = log.lines().filter(line -> NOISE.matcher(line).find()).toList();
            assertThat(noise).as("WARN or ERROR lines while starting (run " + (run + 1) + ")").isEmpty();
        }
    }
}
