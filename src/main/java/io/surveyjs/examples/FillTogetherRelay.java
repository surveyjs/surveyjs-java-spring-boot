package io.surveyjs.examples;

import java.util.Map;
import java.util.regex.Pattern;

import io.surveyjs.integration.StoredJson;
import io.surveyjs.relay.FillRoom;
import io.surveyjs.relay.Participant;
import io.surveyjs.relay.RoomKey;
import io.surveyjs.security.User;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
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
import tools.jackson.databind.node.ObjectNode;

/**
 * Fill Forms Together: the relay behind CollaborationPlugin from survey-core/collaboration, a WebSocket handler at
 * /ws/rooms/{recordId} on the app's port (relay/RelayConfig). Wire protocol:
 * https://github.com/surveyjs/collaborative-form-filling (PROTOCOL.md). The rooms are in relay/FillRoom. The relay saves
 * the finished job sheet itself, once per room session.
 */
@Component
public class FillTogetherRelay extends TextWebSocketHandler implements HandshakeInterceptor {

    private static final Log log = LogFactory.getLog(FillTogetherRelay.class);
    private static final Pattern RECORD_ID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");   // the ids the reference relay accepts

    private final FillRoom.Rooms rooms;
    private final RoomKey roomKeys;
    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final StoredJson storedJson;

    public FillTogetherRelay(FillRoom.Rooms rooms, RoomKey roomKeys, JdbcClient jdbc, JsonMapper json, StoredJson storedJson) {
        this.rooms = rooms;
        this.roomKeys = roomKeys;
        this.jdbc = jdbc;
        this.json = json;
        this.storedJson = storedJson;
    }

    /** The job sheet definition, and the saved answers when the record exists (otherwise a blank sheet). */
    private FillRoom.Rooms.Seed seed(String recordId) {
        String form = jdbc.sql("SELECT json FROM forms WHERE key = 'job-sheet'").query(String.class).optional().orElse("{}");
        JsonNode saved = jdbc.sql("SELECT data FROM responses WHERE form_id = 'job-sheet' AND CAST(id AS VARCHAR) = ?").param(recordId)
                .query(String.class).optional().map(json::readTree).orElse(null);
        return new FillRoom.Rooms.Seed(json.readTree(form), saved instanceof ObjectNode values ? values : json.createObjectNode());
    }

    // #region sjs:fill-together.server
    // The relay runs in this app's process, on its port, and relays JSON: it never parses the definition
    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler handler,
                                   Map<String, Object> attributes) {
        String recordId = request.getURI().getPath().substring("/ws/rooms/".length());
        // Spring Security's filter chain ran on the upgrade request: the demo_user cookie stands in for your real session check
        User user = request.getPrincipal() instanceof Authentication auth && auth.getPrincipal() instanceof User u ? u : null;
        String roomKey = roomKeys.forId(recordId);
        if (!RECORD_ID.matcher(recordId).matches() || user == null || roomKey == null) {
            response.setStatusCode(HttpStatus.FORBIDDEN);   // the browser sees a refused handshake
            return false;
        }
        attributes.putAll(Map.of("user", user, "recordId", recordId, "roomKey", roomKey));
        return true;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        User user = (User) session.getAttributes().get("user");
        FillRoom room = rooms.join(roomKey(session), () -> seed((String) session.getAttributes().get("recordId")));   // definition + saved answers
        room.lock().lock();
        try {
            Participant client = room.add(session, Participant.displayName(session, user.name()));
            client.send(Participant.message("type", "init", "clientId", client.id(), "name", client.name(), "colorIndex", room.colorIndex(client),
                    "seed", room.seed(), "values", room.values(), "peers", room.roster(client),
                    "sessionId", room.sessionId(), "responseId", room.responseId()));   // init first
        } finally {
            room.lock().unlock();
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage text) {
        JsonNode message = parse(text.getPayload());   // objects stay objects: {} is relayed as {}
        FillRoom room = rooms.room(roomKey(session));
        Participant client = Participant.of(session);
        switch (message.path("type").asString("")) {
            case "complete" -> {
                if (message.get("data") instanceof ObjectNode data) {
                    complete(room, client, data);
                }
            }
            case "value" -> {
                if (message.path("key").isString()) {   // last write wins; ignored once the room's response is saved
                    room.value(client, message.get("key").asString(), message.path("value").isMissingNode() ? null : message.get("value"));
                }
            }
            case "presence" -> {
                if (message.hasNonNull("state") && text.getPayloadLength() <= 4096) {   // stamp identity, relay
                    room.presence(client, message.get("state"), !message.path("retain").isBoolean() || message.get("retain").asBoolean());
                }
            }
            default -> { }   // unknown types: ignore
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        rooms.leave(roomKey(session), Participant.of(session));   // peer-left to the others; the room is dropped 2 s after the last leaves
    }
    // #endregion

    // #region sjs:fill-together.complete
    // Save once per room session and send the same confirmation to every participant
    private void complete(FillRoom room, Participant sender, ObjectNode data) {
        room.lock().lock();   // two completes at once: the second waits, then finds the saved id
        try {
            if (room.responseId() == null) {
                try {
                    room.saved(storedJson.insertResponse("job-sheet", data), data);   // as save-response stores it
                } catch (RuntimeException e) {
                    log.error("fill-together: couldn't save the job sheet", e);
                    sender.send(Participant.message("type", "save-error", "error", "Couldn't save the job sheet. Please try again."));
                    return;
                }
            }
            room.sendAll(Participant.message("type", "completed", "id", room.responseId(), "data", room.values()));
        } finally {
            room.lock().unlock();
        }
    }
    // #endregion

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler handler, Exception exception) {
    }

    private static String roomKey(WebSocketSession session) {
        return (String) session.getAttributes().get("roomKey");
    }

    /** A message as JSON, or a missing node: NaN and broken JSON are ignored like unknown types. */
    private JsonNode parse(String text) {
        try {
            return json.readTree(text);
        } catch (JacksonException e) {
            return json.missingNode();
        }
    }
}
