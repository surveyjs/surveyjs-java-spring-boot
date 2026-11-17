package io.surveyjs.integration;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * A small in-memory cache with an expiry per entry, for the countries list (choices-from-web): 24 hours for the
 * live list, 10 minutes for the bundled fallback. Spring's cache abstraction has no per-entry expiry without a
 * provider such as Caffeine. It lives in this process: with several processes or servers, use a shared cache
 * (Redis, or a table) so they don't each call restcountries.com.
 */
@Component
public class TtlCache {

    private record Entry(Object value, long expiresAt) {
    }

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    /** The value, or null when it is missing or expired. */
    @SuppressWarnings("unchecked")
    public <T> T get(String key) {
        Entry entry = entries.get(key);
        if (entry == null) {
            return null;
        }
        if (entry.expiresAt() - System.nanoTime() <= 0) {
            entries.remove(key, entry);
            return null;
        }
        return (T) entry.value();
    }

    public boolean has(String key) {
        return get(key) != null;
    }

    public void put(String key, Object value, Duration ttl) {
        entries.put(key, new Entry(value, System.nanoTime() + ttl.toNanos()));
    }

    public void clear() {
        entries.clear();
    }
}
