package io.surveyjs.examples;

import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;

import io.surveyjs.integration.TtlCache;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Load Choices from Web: GET /api/offices?region= and GET /api/countries, a proxy for restcountries.com.
 */
@RestController
public class ChoicesFromWebController {

    private static final Log log = LogFactory.getLog(ChoicesFromWebController.class);
    private static final Path BUNDLED_COUNTRIES = Path.of("shared", "seed", "countries.json");

    public record Office(String id, String name) {
    }

    public record Country(String code, String name) {
    }

    private final JdbcClient jdbc;
    private final TtlCache cache;
    private final JsonMapper json;
    private final RestClient restcountries;

    public ChoicesFromWebController(JdbcClient jdbc, TtlCache cache, JsonMapper json) {
        this.jdbc = jdbc;
        this.cache = cache;
        this.json = json;
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
        factory.setReadTimeout(Duration.ofSeconds(5));
        this.restcountries = RestClient.builder().requestFactory(factory).build();
    }

    // #region sjs:choices-from-web.server
    // GET /api/offices?region= — {region} from the form arrives as a query parameter
    @GetMapping("/api/offices")
    public List<Office> offices(@RequestParam(defaultValue = "") String region) {
        return jdbc.sql("SELECT id, name FROM offices WHERE region = ? ORDER BY name").param(region).query(Office.class).list();
    }

    // GET /api/countries — a proxy: the browser can't call a service without CORS, your server can
    @GetMapping("/api/countries")
    public ResponseEntity<List<Country>> countries() {
        List<Country> countries = cache.get("countries");
        if (countries == null) {
            try {
                JsonNode all = restcountries.get().uri("https://restcountries.com/v3.1/all?fields=name,cca2").retrieve().body(JsonNode.class);
                countries = all.valueStream().map(c -> new Country(c.get("cca2").asString(), c.get("name").get("common").asString()))
                        .sorted(Comparator.comparing(Country::name)).toList();
                cache.put("countries", countries, Duration.ofHours(24));
            } catch (RuntimeException e) {   // down, or a different answer: the bundled list for 10 minutes, not a timeout each
                log.warn("restcountries.com failed (" + e.getMessage() + "); serving shared/seed/countries.json");
                countries = json.readValue(BUNDLED_COUNTRIES.toFile(), new TypeReference<List<Country>>() { });
                cache.put("countries", countries, Duration.ofMinutes(10));
            }
        }
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePublic()).body(countries);
    }
    // #endregion
}
