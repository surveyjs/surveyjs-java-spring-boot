package io.surveyjs.demo;

import java.time.Duration;

import io.surveyjs.security.User;
import io.surveyjs.security.UserStore;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Demo only: each example page signs in as its own user. Before the page handlers ({@code /examples/**} and
 * {@code /claim}) it keeps the cookie's user if the page offers it; otherwise it signs the request in as the page's
 * default and sets {@code demo_user} to it. host.js writes the cookie back on each tab's focus, so it isn't HttpOnly.
 * So Option A's {@code @AuthenticationPrincipal} is already the page's user.
 */
@Component
public class PageUserInterceptor implements HandlerInterceptor {

    private static final Duration YEAR = Duration.ofDays(365);

    private final UserStore users;

    public PageUserInterceptor(UserStore users) {
        this.users = users;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String slug = slug(request.getRequestURI());
        if (slug == null) {
            return true;
        }
        Authentication current = SecurityContextHolder.getContext().getAuthentication();
        String signedIn = current != null && current.getPrincipal() instanceof User user ? user.key() : null;
        String key = DemoUsers.pageUser(slug, signedIn);
        if (!key.equals(signedIn)) {
            users.find(key).ifPresent(DemoUserFilter::signIn);
        }
        if (!key.equals(cookie(request))) {
            response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from("demo_user", key)
                    .path("/").maxAge(YEAR).sameSite("Lax").secure(request.isSecure()).build().toString());
        }
        return true;
    }

    /** The example a page path shows; /claim is Option A of load-definition-variables-data. */
    private static String slug(String path) {
        if (path.equals("/claim")) {
            return "load-definition-variables-data";
        }
        if (path.startsWith("/examples/") && path.indexOf('/', "/examples/".length()) < 0) {
            return path.substring("/examples/".length());
        }
        return null;
    }

    private static String cookie(HttpServletRequest request) {
        if (request.getCookies() != null) {
            for (var cookie : request.getCookies()) {
                if (cookie.getName().equals("demo_user")) {
                    return cookie.getValue();
                }
            }
        }
        return null;
    }
}
