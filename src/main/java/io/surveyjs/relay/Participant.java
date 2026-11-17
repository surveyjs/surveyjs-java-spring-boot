package io.surveyjs.relay;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * One participant's WebSocket in a relay room. {@code WebSocketSession.sendMessage} isn't safe to call from two
 * threads at once, and a broadcast reaches many sockets from whichever thread received the message, so each session
 * is wrapped in Spring's {@link ConcurrentWebSocketSessionDecorator}: one sender at a time, the rest buffered in order
 * (up to 16 MB, as an edit room's init can carry a long log; 10 s per send). Used by FillRoom and EditRoom.
 */
public final class Participant {

    static final String ATTRIBUTE = "relay.participant";
    private static final Log log = LogFactory.getLog(Participant.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int SEND_TIME_LIMIT = 10_000;
    private static final int BUFFER_LIMIT = 16 * 1024 * 1024;

    private final WebSocketSession session;
    private final JsonMapper json;
    private final String id;
    private final String name;
    private final int slot;
    private JsonNode state;   // the last presence this participant shared (retained), or null

    Participant(WebSocketSession session, JsonMapper json, String name, int slot) {
        this.session = new ConcurrentWebSocketSessionDecorator(session, SEND_TIME_LIMIT, BUFFER_LIMIT);
        this.json = json;
        this.id = hex(8);
        this.name = name;
        this.slot = slot;
        session.getAttributes().put(ATTRIBUTE, this);
    }

    /** The participant of a session, once it joined a room. */
    public static Participant of(WebSocketSession session) {
        return (Participant) session.getAttributes().get(ATTRIBUTE);
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public int slot() {
        return slot;
    }

    public JsonNode state() {
        return state;
    }

    void state(JsonNode state) {
        this.state = state;
    }

    /** Bytes queued for this socket but not sent yet: a slow peer's backlog grows. */
    int backlog() {
        return ((ConcurrentWebSocketSessionDecorator) session).getBufferSize();
    }

    /** Sends a message as JSON; it goes out after everything sent to this socket before it. */
    public void send(Map<String, Object> message) {
        try {
            session.sendMessage(new TextMessage(json.writeValueAsString(message)));
        } catch (IOException | IllegalStateException e) {   // the socket closed under us: afterConnectionClosed cleans up
            log.debug("relay: stopped sending to " + id + ": " + e.getMessage());
        }
    }

    /** A message with its keys in this order (null values kept): {@code message("type", "init", "clientId", …)}. */
    public static Map<String, Object> message(Object... keysAndValues) {
        Map<String, Object> message = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            message.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return message;
    }

    /** The name from ?name=, trimmed to 32 characters, or the user's name. */
    public static String displayName(WebSocketSession session, String fallback) {
        String name = session.getUri() == null ? null : UriComponentsBuilder.fromUri(session.getUri()).build().getQueryParams().getFirst("name");
        String decoded = name == null ? "" : UriUtils.decode(name, StandardCharsets.UTF_8).strip();
        return decoded.isEmpty() ? fallback : decoded.substring(0, Math.min(32, decoded.length()));
    }

    static String hex(int bytes) {
        byte[] bits = new byte[bytes];
        RANDOM.nextBytes(bits);
        return HexFormat.of().formatHex(bits);
    }
}
