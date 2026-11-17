package io.surveyjs.demo;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Demo only: on the hosted demo (DEMO_MODE=true), /api/translate and /api/work-orders/extract allow 20 calls per
 * visitor (demo_sid) per UTC day. The counts live in this process (the app runs one, see README): with several
 * processes, keep them in a shared store such as Redis or a table. web/WebConfig adds it in demo mode only.
 */
public class AiLimitInterceptor implements HandlerInterceptor {

    static final int LIMIT = 20;

    private final Map<String, Integer> calls = new ConcurrentHashMap<>();   // "<demo_sid>:<YYYY-MM-DD>" => count

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!"POST".equals(request.getMethod())) {
            return true;
        }
        String key = SandboxContext.id() + ":" + LocalDate.now(ZoneOffset.UTC);
        int count = calls.merge(key, 1, Integer::sum);
        if (count > LIMIT) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "The demo allows 20 AI calls per visitor per day");
        }
        if (calls.size() > 10_000) {   // forget yesterday's visitors
            String today = ":" + LocalDate.now(ZoneOffset.UTC);
            calls.keySet().removeIf(k -> !k.endsWith(today));
        }
        return true;
    }
}
