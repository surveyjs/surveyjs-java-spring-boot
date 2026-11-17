package io.surveyjs.integration;

import java.net.ConnectException;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.surveyjs.config.SurveyJsProperties;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Calls the SurveyJS service (SurveyJS Server, SURVEYJS_SERVICE_URL) for Run Server-Side Workflows: validate-response
 * validates, lint-definition lints, server-pdf prints a PDF and extract-from-paper extracts. It turns what isn't an
 * answer (a timeout, a refused connection) into the same {@code { error }} on every platform, so the controllers only
 * match the answers they recognize. When you copy one of those examples, copy this class too.
 *
 * <p>It sends with {@code exchange()}, which never throws for a status: the service's 422s are answers here, and it
 * answers some errors with {@code 200 { error }}, so a status alone never means success. Each controller compares
 * the answer itself and fails closed.
 */
@Service
public class SurveyJsService {

    private static final Log log = LogFactory.getLog(SurveyJsService.class);

    /** What the service answered: status, Content-Type and the body's bytes. */
    public record ServiceAnswer(int status, String contentType, byte[] bytes) {

        /** The body as parsed JSON, or null when it isn't JSON. */
        public JsonNode json() {
            try {
                return bytes.length == 0 ? null : JsonMapper.shared().readTree(bytes);
            } catch (JacksonException notJson) {
                return null;
            }
        }

        /** Whether the Content-Type is this media type (parameters such as charset aside). */
        public boolean hasContentType(String type) {
            return contentType != null && contentType.toLowerCase().startsWith(type);
        }

        /** Whether the body starts with these bytes, e.g. "%PDF-". */
        public boolean startsWith(String prefix) {
            byte[] head = prefix.getBytes(StandardCharsets.ISO_8859_1);
            return bytes.length >= head.length && java.util.Arrays.equals(bytes, 0, head.length, head, 0, head.length);
        }

        /** For the log: a JSON answer's first 2000 characters, otherwise only its size (a PDF's bytes never reach the log). */
        String text() {
            return hasContentType("application/json")
                    ? new String(bytes, 0, Math.min(bytes.length, 2000), StandardCharsets.UTF_8)
                    : "(" + contentType + ", " + bytes.length + " bytes)";
        }
    }

    private final String baseUrl;
    private final JsonMapper json;
    private final Map<Duration, RestClient> clients = new ConcurrentHashMap<>();

    public SurveyJsService(SurveyJsProperties settings, JsonMapper json) {
        this.baseUrl = settings.serviceUrl();
        this.json = json;
    }

    /**
     * POSTs JSON to the service and returns its answer, whatever the status. When nothing answers, the request ends
     * here: 504 when the service timed out, 503 when it isn't running, 502 when the connection broke.
     */
    public ServiceAnswer post(String path, Object body, Duration timeout) {
        try {
            return client(timeout).post().uri(baseUrl + path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(json.writeValueAsBytes(body))   // 1.0 stays 1.0, "naïve" stays readable
                    .exchange((request, response) -> new ServiceAnswer(response.getStatusCode().value(),
                            response.getHeaders().getFirst("Content-Type"), response.getBody().readAllBytes()));
        } catch (ResourceAccessException e) {
            if (causedBy(e, HttpTimeoutException.class)) {
                log.error("SurveyJS service: " + path + " timed out after " + timeout.toMillis() / 1000.0 + " s");
                throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "SurveyJS service timed out");
            }
            if (causedBy(e, ConnectException.class)) {
                log.error("SurveyJS service: nothing answers at " + baseUrl);
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "SurveyJS service is not running at " + baseUrl);
            }
            log.error("SurveyJS service: " + path + " failed: " + e.getMostSpecificCause().getClass().getSimpleName());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "SurveyJS service error");
        }
    }

    /** An answer the controller doesn't recognize: logged, and the request ends with 502 { error }. */
    public ResponseStatusException unexpected(String path, ServiceAnswer answer) {
        return unexpected(path, answer, "SurveyJS service error");
    }

    public ResponseStatusException unexpected(String path, ServiceAnswer answer, String message) {
        log.error("SurveyJS service: unexpected answer from " + path + ": " + answer.status() + " " + answer.text());
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, message);
    }

    /** Extraction answers are logged by status only, never by body: it may hold the answers written on the form. */
    public ResponseStatusException unexpectedExtraction(ServiceAnswer answer) {
        log.error("SurveyJS service: unexpected answer from /extract: " + answer.status());
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, "SurveyJS service error");
    }

    /** One client per timeout, built once: a per-call timeout isn't a RestClient option. */
    private RestClient client(Duration timeout) {
        return clients.computeIfAbsent(timeout, t -> {
            JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                    HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
            factory.setReadTimeout(t);
            return RestClient.builder().requestFactory(factory).build();
        });
    }

    private static boolean causedBy(Throwable e, Class<? extends Throwable> type) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (type.isInstance(cause)) {
                return true;
            }
        }
        return false;
    }
}
