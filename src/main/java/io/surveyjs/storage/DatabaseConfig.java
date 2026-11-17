package io.surveyjs.storage;

import java.nio.file.Path;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariDataSource;
import io.surveyjs.config.SurveyJsProperties;
import io.surveyjs.demo.SandboxDataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The app's database: H2, the pure-Java database, embedded in this JVM in file mode under {@code DATA_DIR/database}.
 * Spring Boot builds the {@code JdbcClient}, the transaction manager and the schema initialization (schema.sql)
 * on this {@link DataSource}. One process owns the files: H2 locks them while the app runs.
 *
 * <p>Using PostgreSQL instead: replace this bean with Boot's {@code spring.datasource.*} properties, and keep the
 * JSON columns {@code text} (README: The database).
 */
@Configuration(proxyBeanMethods = false)
public class DatabaseConfig {

    @Bean
    public DataSource dataSource(SurveyJsProperties settings, ObjectProvider<Seeder> seeder) {
        HikariDataSource database = pool(settings.databaseDir(), 10);
        // Demo only: in demo mode each visitor's requests go to a private copy (README: How data works in the demo)
        return settings.demoMode() ? new SandboxDataSource(database, settings, seeder) : database;
    }

    /** A connection pool on the H2 database in this folder. */
    public static HikariDataSource pool(Path folder, int size) {
        HikariDataSource pool = new HikariDataSource();
        pool.setJdbcUrl(url(folder));
        pool.setUsername("sa");
        pool.setPassword("");
        pool.setMaximumPoolSize(size);
        pool.setPoolName("h2-" + folder.getFileName());
        return pool;
    }

    /**
     * The H2 URL of the database in this folder. NON_KEYWORDS=KEY: the contract's {@code key} columns.
     * DATABASE_TO_LOWER=TRUE: column labels come back lower-case ({@code form_id}), as on SQLite.
     * DB_CLOSE_ON_EXIT=FALSE: Spring closes the pool on shutdown. The path must be absolute.
     */
    public static String url(Path folder) {
        String path = folder.toAbsolutePath().resolve("surveyjs").toString().replace('\\', '/');
        return "jdbc:h2:file:" + path + ";NON_KEYWORDS=KEY;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE";
    }
}
