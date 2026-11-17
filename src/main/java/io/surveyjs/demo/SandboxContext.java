package io.surveyjs.demo;

import java.util.Map;

import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;
import org.springframework.web.socket.server.HandshakeInterceptor;

/**
 * Demo only: the visitor sandbox the current thread works for, or null (the app's own database and uploads).
 * {@link SandboxFilter} binds it for each HTTP request; for a relay connection it travels in the WebSocket
 * session's attributes, and {@link #around(WebSocketHandler)} binds it for each callback, because the message
 * thread isn't the request thread. {@link SandboxDataSource} and {@code FileStore} read it.
 */
public final class SandboxContext {

    static final String ATTRIBUTE = "demo.sandboxId";
    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private SandboxContext() {
    }

    /** The current sandbox id (32 hex characters), or null. */
    public static String id() {
        return CURRENT.get();
    }

    /** Runs with this sandbox bound to the current thread, and unbinds it afterwards. */
    public static void run(String sandboxId, ThrowingRunnable work) throws Exception {
        String previous = CURRENT.get();
        set(sandboxId);
        try {
            work.run();
        } finally {
            set(previous);
        }
    }

    static void set(String sandboxId) {
        if (sandboxId == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(sandboxId);
        }
    }

    @FunctionalInterface
    public interface ThrowingRunnable {
        void run() throws Exception;
    }

    /** Copies the upgrade request's sandbox id into the WebSocket session's attributes. */
    public static HandshakeInterceptor handshake() {
        return new HandshakeInterceptor() {
            @Override
            public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler handler,
                                           Map<String, Object> attributes) {
                if (id() != null) {
                    attributes.put(ATTRIBUTE, id());
                }
                return true;
            }

            @Override
            public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler handler,
                                       Exception exception) {
            }
        };
    }

    /** A relay handler that runs each callback with the connection's sandbox bound. */
    public static WebSocketHandler around(WebSocketHandler relay) {
        return new WebSocketHandlerDecorator(relay) {
            @Override
            public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                run(sandbox(session), () -> super.afterConnectionEstablished(session));
            }

            @Override
            public void handleMessage(WebSocketSession session, WebSocketMessage<?> message) throws Exception {
                run(sandbox(session), () -> super.handleMessage(session, message));
            }

            @Override
            public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
                run(sandbox(session), () -> super.afterConnectionClosed(session, status));
            }
        };
    }

    private static String sandbox(WebSocketSession session) {
        return (String) session.getAttributes().get(ATTRIBUTE);
    }
}
