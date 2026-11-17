package io.surveyjs.demo;

import java.io.IOException;

import io.surveyjs.security.User;
import io.surveyjs.security.UserStore;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Demo only: signs the request in as the user the {@code demo_user} cookie names (alice or bob). An absent, unknown or
 * {@code none} cookie leaves the request anonymous: signed out, which is what the contract test sends.
 *
 * <p>It runs in Spring Security's filter chain, before {@code AnonymousAuthenticationFilter}
 * (security/SecurityConfig). A real app authenticates here with form login, OAuth2 login or a JWT resource server,
 * and handlers keep taking {@code @AuthenticationPrincipal User user}.
 */
public class DemoUserFilter extends OncePerRequestFilter {

    private final UserStore users;

    public DemoUserFilter(UserStore users) {
        this.users = users;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.equals("/up") || path.startsWith("/shared/") || path.startsWith("/css/");   // never the database
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String key = cookie(request);
        if (key != null && DemoUsers.USERS.contains(key)) {
            users.find(key).ifPresent(DemoUserFilter::signIn);
        }
        chain.doFilter(request, response);
    }

    /** Makes this user the request's principal: {@code @AuthenticationPrincipal User user} in the handlers. */
    static void signIn(User user) {
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(user, null, user.getAuthorities()));
        SecurityContextHolder.setContext(context);
    }

    private static String cookie(HttpServletRequest request) {
        if (request.getCookies() != null) {
            for (Cookie cookie : request.getCookies()) {
                if (cookie.getName().equals("demo_user")) {
                    return cookie.getValue();
                }
            }
        }
        return null;
    }
}
