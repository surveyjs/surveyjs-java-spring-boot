package io.surveyjs.examples;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Async Calculations and Validation: GET /api/customers/exists?email= and GET /api/shipping?postcode=.
 */
@RestController
public class AsyncFunctionsController {

    /** Only a whole German (10115) or UK (SW1A 1AA) postcode gets a price, not anything that starts with a prefix. */
    private static final Pattern POSTCODE = Pattern.compile("^(\\d{5}|[A-Z]{1,2}\\d[A-Z\\d]?\\d[A-Z]{2})$");

    /** The shipping price, or null: written as { "price": null }. */
    public record Price(Double price) {
    }

    private final JdbcClient jdbc;

    public AsyncFunctionsController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // #region sjs:async-functions.server
    // GET /api/customers/exists?email= — answers emailExists()
    @GetMapping("/api/customers/exists")
    public Map<String, Boolean> customerExists(@RequestParam(defaultValue = "") String email) {
        boolean exists = jdbc.sql("SELECT COUNT(*) FROM customers WHERE email = ?")
                .param(email.strip().toLowerCase(Locale.ROOT)).query(Integer.class).single() > 0;
        return Map.of("exists", exists);
    }

    // GET /api/shipping?postcode= — answers shippingCost(): the longest matching postcode prefix wins,
    // { "price": null } when none matches (the form shows "We don't deliver to this postcode yet")
    @GetMapping("/api/shipping")
    public Price shipping(@RequestParam(defaultValue = "") String postcode) {
        String code = postcode.replace(" ", "").toUpperCase(Locale.ROOT);
        if (!POSTCODE.matcher(code).matches()) {
            return new Price(null);
        }
        return new Price(jdbc.sql("SELECT price FROM shipping_rates WHERE ? LIKE postcode_prefix || '%'"
                        + " ORDER BY LENGTH(postcode_prefix) DESC LIMIT 1")
                .param(code).query(Double.class).optional().orElse(null));
    }
    // #endregion
}
