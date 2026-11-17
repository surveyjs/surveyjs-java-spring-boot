package io.surveyjs.demo;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariDataSource;
import io.surveyjs.config.SurveyJsProperties;
import io.surveyjs.storage.DatabaseConfig;
import io.surveyjs.storage.Seeder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

/**
 * Demo only: the app's {@link DataSource} in demo mode. A request bound to a visitor sandbox ({@link SandboxContext})
 * gets a connection to that visitor's private H2 database at {@code DATA_DIR/sandbox/<id>/database}; anything else
 * gets the app's own database. Handler code doesn't change.
 *
 * <p>A sandbox is created on first use, once per id: a new H2 file, schema.sql, then {@link Seeder}. That is the H2
 * form of "a private copy of the seeded database" (H2 holds the main database's files open, so they can't be
 * copied). Each sandbox has a small connection pool of its own (three connections); {@link SandboxPruner} shuts its
 * database down and closes the pool before deleting the files.
 */
public class SandboxDataSource extends AbstractRoutingDataSource implements AutoCloseable {

    private static final Duration TOUCH_EVERY = Duration.ofMinutes(1);

    private final HikariDataSource database;
    private final SurveyJsProperties settings;
    private final ObjectProvider<Seeder> seeder;
    private final Map<String, HikariDataSource> sandboxes = new ConcurrentHashMap<>();

    public SandboxDataSource(HikariDataSource database, SurveyJsProperties settings, ObjectProvider<Seeder> seeder) {
        this.database = database;
        this.settings = settings;
        this.seeder = seeder;
        setTargetDataSources(Map.of());
        setDefaultTargetDataSource(database);
    }

    @Override
    protected Object determineCurrentLookupKey() {
        return SandboxContext.id();
    }

    @Override
    protected DataSource determineTargetDataSource() {
        String id = SandboxContext.id();
        return id == null ? database : sandboxes.computeIfAbsent(id, this::open);
    }

    /** Whether this sandbox has a database (an invite link may name one that was removed). */
    public boolean exists(String id) {
        return sandboxes.containsKey(id) || Files.exists(folder(id).resolve("database").resolve("surveyjs.mv.db"));
    }

    /** Marks the sandbox as used now, at most once a minute: SandboxPruner removes sandboxes unused for 24 hours. */
    public void touch(String id) {
        Path marker = folder(id).resolve("last-used");
        try {
            if (Files.exists(marker) && Files.getLastModifiedTime(marker).toInstant().isBefore(Instant.now().minus(TOUCH_EVERY))) {
                Files.setLastModifiedTime(marker, FileTime.from(Instant.now()));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** When the sandbox was last used, or null if it has no marker. */
    Instant lastUsed(String id) throws IOException {
        Path marker = folder(id).resolve("last-used");
        return Files.exists(marker) ? Files.getLastModifiedTime(marker).toInstant() : null;
    }

    /** The sandbox ids on disk. */
    Stream<String> ids() throws IOException {
        Path root = settings.sandboxDir();
        if (!Files.isDirectory(root)) {
            return Stream.empty();
        }
        try (Stream<Path> folders = Files.list(root)) {
            return folders.map(path -> path.getFileName().toString()).filter(SandboxFilter::isValidId).toList().stream();
        }
    }

    /** Closes the sandbox's pool (H2 SHUTDOWN, then dispose) and deletes its folder: Windows can't delete an open H2 file. */
    void remove(String id) throws IOException {
        HikariDataSource pool = sandboxes.remove(id);
        if (pool != null) {
            shutdown(pool);
        }
        Path folder = folder(id);
        if (Files.exists(folder)) {
            try (Stream<Path> files = Files.walk(folder)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(file);
                }
            }
        }
    }

    private HikariDataSource open(String id) {
        Path folder = folder(id);
        try {
            Files.createDirectories(folder);
            Path marker = folder.resolve("last-used");
            if (!Files.exists(marker)) {
                Files.createFile(marker);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        HikariDataSource pool = DatabaseConfig.pool(folder.resolve("database"), 3);
        pool.setMinimumIdle(0);
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(pool);
        seeder.getObject().seed(pool);
        return pool;
    }

    private Path folder(String id) {
        return settings.sandboxDir().resolve(id);
    }

    private static void shutdown(HikariDataSource pool) {
        try (Connection connection = pool.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("SHUTDOWN");
        } catch (SQLException e) {
            // already closed
        }
        pool.close();
    }

    @Override
    public void close() {
        sandboxes.values().forEach(SandboxDataSource::shutdown);
        sandboxes.clear();
        database.close();
    }
}
