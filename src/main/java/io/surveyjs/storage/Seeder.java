package io.surveyjs.storage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Map;

import javax.sql.DataSource;

import io.surveyjs.integration.StoredJson;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Seeds the tables from {@code shared/seed/*.json}, the files every platform seeds from.
 *
 * <p>Idempotent: an existing database keeps its data and only gains the forms it is missing; the other tables are
 * seeded only when they are empty, in one transaction. It runs at every start, after schema.sql and before the web
 * server accepts a request ({@code @DependsOnDatabaseInitialization}: a fresh DATA_DIR is ready on the first
 * request), and for each visitor sandbox in demo mode ({@link #seed(DataSource)} on the sandbox's database).
 */
@Component
@DependsOnDatabaseInitialization
public class Seeder implements InitializingBean {

    private static final Log log = LogFactory.getLog(Seeder.class);

    private final DataSource dataSource;
    private final JsonMapper json;

    public Seeder(DataSource dataSource, JsonMapper json) {
        this.dataSource = dataSource;
        this.json = json;
    }

    @Override
    public void afterPropertiesSet() {
        if (seed(dataSource)) {
            log.info("Created and seeded the database from shared/seed");
        }
    }

    /** Seeds the database behind this DataSource. Returns true when it seeded an empty one. */
    public boolean seed(DataSource database) {
        JdbcClient db = JdbcClient.create(database);
        // Every form in seed/forms.json, also into an existing database that lacks one
        for (Map.Entry<String, JsonNode> form : read("seed/forms.json").properties()) {
            boolean missing = db.sql("SELECT COUNT(*) FROM forms WHERE key = ?").param(form.getKey()).query(Integer.class).single() == 0;
            if (missing) {
                String definition = write(read("definitions/" + form.getValue().asString()));
                db.sql("INSERT INTO forms (key, json) VALUES (?, ?)").params(form.getKey(), definition).update();
            }
        }
        if (db.sql("SELECT COUNT(*) FROM users").query(Integer.class).single() > 0) {
            return false;
        }
        new TransactionTemplate(new DataSourceTransactionManager(database)).executeWithoutResult(status -> seedRows(db));
        return true;
    }

    private void seedRows(JdbcClient db) {
        // Demo only: Alice (premium, editor) and Bob (basic, viewer); demo/DemoUserFilter signs them in from a cookie
        db.sql("INSERT INTO users (id, key, name, email, plan, is_editor) VALUES (1, 'alice', 'Alice Martin', 'alice@example.com', 'premium', TRUE)").update();
        db.sql("INSERT INTO users (id, key, name, email, plan, is_editor) VALUES (2, 'bob', 'Bob Fischer', 'bob@example.com', 'basic', FALSE)").update();

        for (JsonNode office : read("seed/offices.json")) {
            db.sql("INSERT INTO offices (id, name, region) VALUES (?, ?, ?)")
                    .params(office.get("id").asString(), office.get("name").asString(), office.get("region").asString()).update();
        }
        for (JsonNode email : read("seed/customers.json")) {
            db.sql("INSERT INTO customers (email) VALUES (?)").param(email.asString()).update();
        }
        for (JsonNode rate : read("seed/shipping-rates.json")) {
            db.sql("INSERT INTO shipping_rates (postcode_prefix, price) VALUES (?, ?)")
                    .params(rate.get("postcode_prefix").asString(), rate.get("price").asDouble()).update();
        }

        for (JsonNode claim : read("seed/claims.json")) {
            JsonNode data = claim.get("data");
            int responseId = response(db, "claim", data, daysAgo(claim.get("daysAgo").asInt()), claim.get("definitionVersion").asString());
            db.sql("INSERT INTO claims (response_id, customer_email, amount) VALUES (?, ?, ?)")
                    .params(responseId, data.get("customer_email").asString(), data.get("amount").asDouble()).update();
        }
        for (JsonNode sheet : read("seed/job-sheets.json")) {
            response(db, "job-sheet", sheet.get("data"), daysAgo(sheet.get("daysAgo").asInt()), null);
        }
        for (JsonNode row : read("seed/feedback-responses.json")) {
            response(db, "feedback", row.get("data"), daysAgo(row.get("daysAgo").asInt()), null);
        }

        for (Map.Entry<String, JsonNode> presets : read("seed/variable-presets.json").properties()) {
            db.sql("INSERT INTO variable_presets (key, json) VALUES (?, ?)").params(presets.getKey(), write(presets.getValue())).update();
        }

        // load-definition-variables-data: one claim request per demo user, saved now
        for (JsonNode data : read("seed/claim-requests.json")) {
            response(db, "claim", data, StoredJson.timestamp(), null);
        }
    }

    private int response(JdbcClient db, String formId, JsonNode data, String createdAt, String version) {
        GeneratedKeyHolder id = new GeneratedKeyHolder();
        db.sql("INSERT INTO responses (form_id, definition_version, data, created_at) VALUES (?, ?, ?, ?)")
                .params(formId, version, write(data), createdAt).update(id, "id");
        return id.getKey().intValue();
    }

    /** The reference's times: {@code days} back, at 09:00 + days % 9 hours and (days * 7) % 60 minutes, UTC. */
    private static String daysAgo(int days) {
        ZonedDateTime moment = ZonedDateTime.now(ZoneOffset.UTC).minusDays(days)
                .withHour(9 + days % 9).withMinute((days * 7) % 60).withSecond(0).withNano(0);
        return StoredJson.timestamp(Instant.from(moment));
    }

    private JsonNode read(String path) {
        try {
            return json.readTree(Files.readString(Path.of("shared").resolve(path)));
        } catch (IOException e) {
            throw new UncheckedIOException("Can't read shared/" + path, e);
        }
    }

    private String write(JsonNode document) {
        return json.writeValueAsString(document);
    }
}
