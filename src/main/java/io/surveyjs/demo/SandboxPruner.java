package io.surveyjs.demo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import javax.sql.DataSource;

import io.surveyjs.config.ConditionalOnSwitch;
import io.surveyjs.config.SurveyJsProperties;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Demo only: every hour, removes visitor sandboxes unused for 24 hours, and relay snapshots as old. The last-used
 * time is a marker file (SandboxDataSource.touch()), so it survives restarts. Runs in demo mode only.
 */
@Component
@ConditionalOnSwitch("surveyjs.demo-mode")
public class SandboxPruner {

    static final Duration UNUSED = Duration.ofHours(24);
    private static final Log log = LogFactory.getLog(SandboxPruner.class);

    private final SandboxDataSource sandboxes;
    private final SurveyJsProperties settings;

    public SandboxPruner(DataSource dataSource, SurveyJsProperties settings) {
        this.sandboxes = (SandboxDataSource) dataSource;
        this.settings = settings;
    }

    @Scheduled(fixedRate = 1, timeUnit = TimeUnit.HOURS)
    public void pruneHourly() {
        try {
            int removed = prune(Instant.now());
            if (removed > 0) {
                log.info("Removed " + removed + " visitor sandboxes unused for 24 hours");
            }
        } catch (IOException e) {
            log.error("Pruning visitor sandboxes failed", e);
        }
    }

    /** Removes what was last used before now - 24 hours. Returns how many sandboxes it removed. */
    public int prune(Instant now) throws IOException {
        Instant cutoff = now.minus(UNUSED);
        int removed = 0;
        for (String id : sandboxes.ids().toList()) {
            Instant used = sandboxes.lastUsed(id);
            if (used == null || used.isBefore(cutoff)) {
                sandboxes.remove(id);   // closes its pool first: Windows can't delete an open H2 file
                removed++;
            }
        }
        Path relay = settings.relayDir();
        if (Files.isDirectory(relay)) {
            List<Path> snapshots;
            try (Stream<Path> files = Files.list(relay)) {
                snapshots = files.filter(file -> file.toString().endsWith(".json")).toList();
            }
            for (Path snapshot : snapshots) {
                if (Files.getLastModifiedTime(snapshot).toInstant().isBefore(cutoff)) {
                    Files.deleteIfExists(snapshot);
                }
            }
        }
        return removed;
    }
}
