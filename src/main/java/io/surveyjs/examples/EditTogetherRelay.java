package io.surveyjs.examples;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import io.surveyjs.relay.EditRoom;
import io.surveyjs.relay.Participant;
import io.surveyjs.relay.RoomKey;
import io.surveyjs.security.User;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Edit Forms Together: the relay behind CollaborationPlugin from survey-creator-core/collaboration, a WebSocket handler
 * at /ws/forms/{formId} on the app's port (relay/RelayConfig). Wire protocol:
 * https://github.com/surveyjs/collaborative-form-editing (PROTOCOL.md). The rooms are in relay/EditRoom. Saving is
 * unchanged: Creator's Save button calls PUT /api/forms/{id} (creator-load-save).
 */
@Component
public class EditTogetherRelay extends TextWebSocketHandler implements HandshakeInterceptor {

    private static final Pattern FORM_ID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    private final EditRoom.Rooms rooms;
    private final RoomKey roomKeys;
    private final JdbcClient jdbc;
    private final JsonMapper json;

    public EditTogetherRelay(EditRoom.Rooms rooms, RoomKey roomKeys, JdbcClient jdbc, JsonMapper json) {
        this.rooms = rooms;
        this.roomKeys = roomKeys;
        this.jdbc = jdbc;
        this.json = json;
    }

    /** The definition saved through creator-load-save, or an empty one for a new form. */
    private JsonNode savedDefinition(String formId) {
        return json.readTree(jdbc.sql("SELECT json FROM forms WHERE key = ?").param(formId).query(String.class).optional().orElse("{}"));
    }

    // #region sjs:edit-together.server
    // The same kind of relay as Fill Forms Together, keeping an append-only log instead of a value map:
    // Creator records must be replayed in order. It runs in this app's process, on its port.
    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler handler,
                                   Map<String, Object> attributes) {
        String formId = request.getURI().getPath().substring("/ws/forms/".length());
        // Spring Security's filter chain ran on the upgrade request: the demo_user cookie stands in for your real session check
        User user = request.getPrincipal() instanceof Authentication auth && auth.getPrincipal() instanceof User u ? u : null;
        String roomKey = roomKeys.forId(formId);
        if (!FORM_ID.matcher(formId).matches() || user == null || !user.editor() || roomKey == null) {   // like saving, editing together is for editors
            response.setStatusCode(HttpStatus.FORBIDDEN);   // the browser sees a refused handshake
            return false;
        }
        attributes.putAll(Map.of("user", user, "formId", formId, "roomKey", roomKey));
        return true;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        User user = (User) session.getAttributes().get("user");
        EditRoom room = rooms.join(roomKey(session), () -> savedDefinition((String) session.getAttributes().get("formId")));   // seed = the saved definition
        room.lock().lock();
        try {
            Participant client = room.add(session, Participant.displayName(session, user.name()));
            client.send(Participant.message("type", "init", "clientId", client.id(), "colorIndex", client.slot(), "color", room.color(client),
                    "seed", room.seed(), "log", room.log()));   // init first
            List<Map<String, Object>> peers = room.roster(client);
            if (!peers.isEmpty()) {
                client.send(Participant.message("type", "presence-sync", "peers", peers));
            }
        } finally {
            room.lock().unlock();
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage text) {
        JsonNode message = parse(text.getPayload());   // records stay opaque: {} is relayed as {}
        EditRoom room = rooms.room(roomKey(session));
        Participant client = Participant.of(session);
        switch (message.path("type").asString("")) {
            case "append" -> {
                if (message.hasNonNull("payload")) {   // append, then fan out
                    room.append(client, message.get("payload"));
                }
            }
            case "presence" -> {
                if (message.hasNonNull("state") && text.getPayloadLength() <= 4096) {
                    room.presence(client, message.get("state"));
                }
            }
            default -> { }   // unknown types: ignore
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        rooms.leave(roomKey(session), Participant.of(session));   // presence-leave to the others; the log lives until the room empties
    }
    // #endregion

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler handler, Exception exception) {
    }

    private static String roomKey(WebSocketSession session) {
        return (String) session.getAttributes().get("roomKey");
    }

    /** A message as JSON, or a missing node: broken JSON is ignored like an unknown type. */
    private JsonNode parse(String text) {
        try {
            return json.readTree(text);
        } catch (JacksonException e) {
            return json.missingNode();
        }
    }
}
