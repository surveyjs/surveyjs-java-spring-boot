package io.surveyjs;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.surveyjs.config.SurveyJsProperties;
import io.surveyjs.integration.SurveyJsService;
import io.surveyjs.integration.SurveyJsService.ServiceAnswer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The real SurveyJsService against a JDK HttpServer stub on a free port: what isn't an answer (a timeout, a refused
 * connection, a broken one) becomes the mapping's 504, 503 and 502; any status the service sends, 422 included, is an
 * answer the controller matches.
 */
class SurveyJsServiceTest {

    HttpServer server;
    String url;
    final AtomicReference<String> received = new AtomicReference<>();

    static SurveyJsService service(String url) {
        SurveyJsProperties settings = new SurveyJsProperties(url, false, false, false, null, null, null, null, null, null, null, null, null,
                null, null, null, Path.of("target", "test-data"), null, null, 8000);
        return new SurveyJsService(settings, JsonMapper.shared());
    }

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/schema", exchange -> {
            received.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8) + " " + exchange.getRequestHeaders().getFirst("Content-Type"));
            send(exchange, 422, "application/json", "{\"errors\":[{\"rule\":\"x\"}],\"warnings\":[]}".getBytes(StandardCharsets.UTF_8));
        });
        server.createContext("/pdf", exchange -> send(exchange, 200, "application/pdf", "%PDF-1.7\n".getBytes(StandardCharsets.ISO_8859_1)));
        server.createContext("/slow", exchange -> {
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            send(exchange, 200, "application/json", "{}".getBytes(StandardCharsets.UTF_8));
        });
        server.createContext("/reset", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, 1000);   // promises 1000 bytes, sends 10, then drops the connection
            OutputStream body = exchange.getResponseBody();
            body.write("{\"data\":{\"".getBytes(StandardCharsets.UTF_8));
            body.flush();
            try {
                body.close();
            } catch (IOException expected) {   // "insufficient bytes written": the stub closes the connection
            }
        });
        server.start();
        url = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    static void send(HttpExchange exchange, int status, String type, byte[] body) throws IOException {
        exchange.getRequestBody().readAllBytes();
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    @Test
    void a422IsAnAnswerNotAFailure() {
        ServiceAnswer answer = service(url).post("/schema", Map.of("title", "naïve", "n", 1.0), Duration.ofSeconds(10));
        assertThat(answer.status()).isEqualTo(422);
        assertThat(answer.contentType()).startsWith("application/json");
        assertThat(answer.json().has("errors")).isTrue();
        assertThat(answer.json().has("warnings")).isTrue();
        assertThat(received.get()).contains("\"title\":\"naïve\"").contains("1.0").endsWith("application/json");
    }

    @Test
    void bytesAndTheContentTypeComeBackAsSent() {
        ServiceAnswer answer = service(url).post("/pdf", Map.of(), Duration.ofSeconds(10));
        assertThat(answer.status()).isEqualTo(200);
        assertThat(answer.hasContentType("application/pdf")).isTrue();
        assertThat(answer.startsWith("%PDF-")).isTrue();
        assertThat(answer.json()).isNull();   // not JSON
    }

    @Test
    void aSlowAnswerTimesOutWith504() {
        long started = System.nanoTime();
        assertThatThrownBy(() -> service(url).post("/slow", Map.of(), Duration.ofMillis(500)))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode().value()).isEqualTo(504);
                    assertThat(e.getReason()).isEqualTo("SurveyJS service timed out");
                });
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(2900));
    }

    @Test
    void aClosedPortIs503WithTheUrl() throws IOException {
        int port;
        try (ServerSocket free = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            port = free.getLocalPort();
        }
        String closed = "http://127.0.0.1:" + port;
        assertThatThrownBy(() -> service(closed).post("/schema", Map.of(), Duration.ofSeconds(10)))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode().value()).isEqualTo(503);
                    assertThat(e.getReason()).isEqualTo("SurveyJS service is not running at " + closed);
                });
    }

    @Test
    void aConnectionDroppedMidAnswerIs502() {
        assertThatThrownBy(() -> service(url).post("/reset", Map.of(), Duration.ofSeconds(10)))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode().value()).isEqualTo(502);
                    assertThat(e.getReason()).isEqualTo("SurveyJS service error");
                });
    }

    @Test
    void aTrailingSlashInTheUrlIsDropped() {
        ServiceAnswer answer = service(url + "/").post("/pdf", Map.of(), Duration.ofSeconds(10));
        assertThat(answer.status()).isEqualTo(200);
    }
}
