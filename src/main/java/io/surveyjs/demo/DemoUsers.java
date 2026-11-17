package io.surveyjs.demo;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Demo only: a {@code demo_user} cookie picks Alice (premium, editor), Bob (basic, viewer) or Signed out ("none").
 * Pages where the user changes the result offer a switch; every other page signs in as Alice, so a choice made on one
 * page never breaks another ({@link PageUserInterceptor}).
 */
public final class DemoUsers {

    public static final List<String> USERS = List.of("alice", "bob");

    public static final Map<String, String> LABELS = labels();

    /** Example slug => the users its switch offers. The first is the default. */
    private static final Map<String, List<String>> PAGES = Map.of(
            "load-definition-variables-data", List.of("alice", "bob"),   // compare premium and basic customer variables
            "resume-progress", List.of("alice", "bob"),                  // keep an independent draft for each respondent
            "creator-load-save", List.of("alice", "bob"));               // Bob's saves answer 403 (edit-forms)

    private DemoUsers() {
    }

    /** The users an example page offers; one means no switch. */
    public static List<String> forPage(String slug) {
        return PAGES.getOrDefault(slug, List.of("alice"));
    }

    /** The cookie's user if the page offers it, otherwise the page's default. */
    public static String pageUser(String slug, String cookie) {
        List<String> users = forPage(slug);
        return cookie != null && users.contains(cookie) ? cookie : users.get(0);
    }

    private static Map<String, String> labels() {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("alice", "Alice (premium, editor)");
        labels.put("bob", "Bob (basic, viewer)");
        labels.put("none", "Signed out");
        return Collections.unmodifiableMap(labels);
    }
}
