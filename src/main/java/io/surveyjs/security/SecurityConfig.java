package io.surveyjs.security;

import java.io.IOException;

import io.surveyjs.demo.DemoUserFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.firewall.HttpStatusRequestRejectedHandler;
import org.springframework.security.web.firewall.RequestRejectedHandler;

/**
 * Who is asking, and may they? Spring Security signs the user in (here: the demo users, from a cookie) and checks
 * the {@code edit-forms} authority on the handlers that save definitions and presets
 * ({@code @PreAuthorize("hasAuthority('edit-forms')")}). The examples answer 401 and 403 themselves, with the
 * contract's texts, so every request is permitted here.
 */
@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, UserStore users) throws Exception {
        return http
                // CSRF off: the demo signs in by a cookie the page sets, not a session, and the shared client and the
                // contract test send plain fetch() calls. A real app with a session cookie keeps CSRF on and sends its
                // token (CookieCsrfTokenRepository with the X-XSRF-TOKEN header; README: Security).
                .csrf(AbstractHttpConfigurer::disable)
                // Stateless: no JSESSIONID, no login form, no basic auth
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                // Demo only: the demo_user cookie picks the user. Your real authentication replaces this line:
                // .oauth2Login(), .formLogin() or .oauth2ResourceServer(jwt -> …)
                .addFilterBefore(new DemoUserFilter(users), AnonymousAuthenticationFilter.class)
                .authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
                // In case anything reaches them: the contract's { error } shape, never a login page
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, e) -> error(request, response, HttpStatus.UNAUTHORIZED, "Sign in first"))
                        .accessDeniedHandler((request, response, e) -> error(request, response, HttpStatus.FORBIDDEN,
                                "You don't have editor rights: only editors can change forms")))
                .build();
    }

    /** The firewall rejects paths such as /shared/../.env: answer 404, as the contract asks, not 400. */
    @Bean
    public RequestRejectedHandler requestRejectedHandler() {
        return new HttpStatusRequestRejectedHandler(HttpStatus.NOT_FOUND.value());
    }

    private static void error(HttpServletRequest request, HttpServletResponse response, HttpStatus status, String message)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"error\":\"" + message.replace("\"", "\\\"") + "\"}");
    }
}
