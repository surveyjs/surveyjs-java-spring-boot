package io.surveyjs.demo;

import java.io.IOException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.regex.Pattern;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Demo only: with DEMO_MODE=true each visitor works in a private copy of the seeded database and an uploads folder,
 * chosen by the {@code demo_sid} cookie. The examples don't change: this filter binds the sandbox id in
 * {@link SandboxContext} for the request, and {@link SandboxDataSource} and {@code FileStore} follow it.
 *
 * <p>It runs before Spring Security's filter chain (web/WebConfig registers it), because signing in the demo user
 * reads the visitor's own users table. An invite link ({@code ?join=<demo_sid>}) adopts the inviter's sandbox.
 * Delete this package when you copy an example into your app.
 */
public class SandboxFilter extends OncePerRequestFilter {

    /** Request attribute: the page header says the invite link has expired. */
    public static final String JOIN_FAILED = "demo.joinFailed";

    private static final Pattern ID = Pattern.compile("^[0-9a-f]{32}$");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Duration MONTH = Duration.ofDays(30);

    private final SandboxDataSource sandboxes;

    public SandboxFilter(SandboxDataSource sandboxes) {
        this.sandboxes = sandboxes;
    }

    /** 128 random bits as 32 lowercase hex characters; nothing else ever reaches a file path. */
    public static boolean isValidId(String value) {
        return value != null && ID.matcher(value).matches();
    }

    static String newId() {
        byte[] bits = new byte[16];
        RANDOM.nextBytes(bits);
        return HexFormat.of().formatHex(bits);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.equals("/up") || path.startsWith("/shared/") || path.startsWith("/css/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String sandboxId = cookie(request, "demo_sid");
        boolean issueCookie = false;

        // An invite link (?join=<id>) adopts the inviter's sandbox, after the same format check
        String join = request.getParameter("join");
        boolean upgrade = "websocket".equalsIgnoreCase(request.getHeader(HttpHeaders.UPGRADE));
        if (join != null && !upgrade && !request.getRequestURI().startsWith("/api/")) {
            if (isValidId(join) && sandboxes.exists(join)) {
                issueCookie = !join.equals(sandboxId);
                sandboxId = join;
            } else {
                request.setAttribute(JOIN_FAILED, true);   // the page header says the invite has expired
                sandboxId = null;
            }
        }

        if (!isValidId(sandboxId)) {
            if (upgrade) {   // a relay can't set a cookie: no sandbox, so the relay refuses the connection
                chain.doFilter(request, response);
                return;
            }
            sandboxId = newId();   // never a file path built from client input
            issueCookie = true;
        }
        if (issueCookie) {
            response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from("demo_sid", sandboxId)
                    .path("/").maxAge(MONTH).httpOnly(true).sameSite("Lax").secure(request.isSecure()).build().toString());
        }
        sandboxes.touch(sandboxId);   // last used: SandboxPruner removes sandboxes unused for 24 hours

        try {
            SandboxContext.run(sandboxId, () -> chain.doFilter(request, response));
        } catch (IOException | ServletException | RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new ServletException(e);
        }
    }

    private static String cookie(HttpServletRequest request, String name) {
        if (request.getCookies() == null) {
            return null;
        }
        for (Cookie cookie : request.getCookies()) {
            if (cookie.getName().equals(name)) {
                return cookie.getValue();
            }
        }
        return null;
    }
}
