package io.surveyjs.demo;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import io.surveyjs.integration.TtlCache;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Demo only: adds {@code X-Demo-Cache: hit / miss / fallback} to GET /api/countries, so the requests log shows where
 * the list came from. As an interceptor it notes whether the cache held the list before the handler ran; as a
 * {@link ResponseBodyAdvice} it adds the header before the body is written ({@code postHandle} would be too late:
 * the answer is already committed). "fallback" wins whenever the bundled list is served, also while it is cached.
 * ChoicesFromWebController stays as the docs show it.
 */
@ControllerAdvice
public class CacheHeaderAdvice implements HandlerInterceptor, ResponseBodyAdvice<Object> {

    private static final String CACHED_BEFORE = "demo.countriesCachedBefore";

    private final TtlCache cache;
    private final JsonMapper json;
    private final JsonNode bundled;

    public CacheHeaderAdvice(TtlCache cache, JsonMapper json) {
        this.cache = cache;
        this.json = json;
        try {
            this.bundled = json.readTree(Files.readString(Path.of("shared", "seed", "countries.json")));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        request.setAttribute(CACHED_BEFORE, cache.has("countries"));
        return true;
    }

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType contentType,
                                  Class<? extends HttpMessageConverter<?>> converterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        if (request instanceof ServletServerHttpRequest servlet && servlet.getServletRequest().getAttribute(CACHED_BEFORE) instanceof Boolean before) {
            Object served = cache.get("countries");
            String state = served != null && json.valueToTree(served).equals(bundled) ? "fallback" : before ? "hit" : "miss";
            response.getHeaders().set("X-Demo-Cache", state);
        }
        return body;
    }
}
