package io.surveyjs;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import io.surveyjs.config.SurveyJsProperties;
import io.surveyjs.relay.RoomKey;
import jakarta.websocket.ContainerProvider;
import jakarta.websocket.WebSocketContainer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

/**
 * The relays, through a real WebSocket client on the app's port: init first with every field, values and records
 * reach the others, complete saves once for everyone, presence-sync, large frames, the handshakes each relay refuses,
 * and (demo mode) rooms scoped to the visitor sandbox.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class RelaysTest extends TestSupport {

    static final StandardWebSocketClient CLIENT = client();

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient jdbc;

    final List<Socket> open = new ArrayList<>();

    static StandardWebSocketClient client() {
        WebSocketContainer container = ContainerProvider.getWebSocketContainer();
        container.setDefaultMaxTextMessageBufferSize(16 * 1024 * 1024);   // an init or a record can be large
        return new StandardWebSocketClient(container);
    }

    @AfterEach
    void closeAll() throws Exception {
        for (Socket socket : open) {
            socket.close();
        }
    }

    /** One participant: its session and every message it received, in order. */
    final class Socket extends TextWebSocketHandler {

        final BlockingQueue<JsonNode> messages = new LinkedBlockingQueue<>();
        WebSocketSession session;

        @Override
        protected void handleTextMessage(WebSocketSession session, TextMessage message) {
            messages.add(json(message.getPayload()));
        }

        JsonNode next() throws InterruptedException {
            JsonNode message = messages.poll(10, TimeUnit.SECONDS);
            if (message == null) {
                fail("no message within 10 s");
            }
            return message;
        }

        /** The next message of a type, skipping the others (a peer's presence, say). */
        JsonNode next(String type) throws InterruptedException {
            for (int i = 0; i < 50; i++) {
                JsonNode message = next();
                if (message.path("type").asString("").equals(type)) {
                    return message;
                }
            }
            throw new AssertionError("no " + type + " message");
        }

        /** Nothing more arrives within this time. */
        void quiet(long millis) throws InterruptedException {
            assertThat(messages.poll(millis, TimeUnit.MILLISECONDS)).isNull();
        }

        void send(String json) throws Exception {
            session.sendMessage(new TextMessage(json));
        }

        void close() throws Exception {
            if (session.isOpen()) {
                session.close(CloseStatus.NORMAL);
            }
        }
    }

    Socket connect(String path, String cookie) throws Exception {
        Socket socket = new Socket();
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        if (cookie != null) {
            headers.add("Cookie", cookie);
        }
        socket.session = CLIENT.execute(socket, headers, URI.create("ws://127.0.0.1:" + port + path)).get(10, TimeUnit.SECONDS);
        open.add(socket);
        return socket;
    }

    Socket connect(String path) throws Exception {
        return connect(path, "demo_user=alice");
    }

    void assertRefused(String path, String cookie) {
        assertThatThrownBy(() -> connect(path, cookie)).as(path + " with " + cookie).isInstanceOf(ExecutionException.class);
    }

    int jobSheets() {
        return jdbc.sql("SELECT COUNT(*) FROM responses WHERE form_id = 'job-sheet'").query(Integer.class).single();
    }

    // ---------- fill-together

    @Test
    void fillInitHasEveryFieldAndValuesReachTheOthers() throws Exception {
        Socket ann = connect("/ws/rooms/relay-a?name=Ann");
        JsonNode init = ann.next();
        assertThat(init.path("type").asString()).isEqualTo("init");
        assertThat(init.propertyNames()).containsExactlyInAnyOrder("type", "clientId", "name", "colorIndex", "seed", "values", "peers",
                "sessionId", "responseId");
        assertThat(init.get("name").asString()).isEqualTo("Ann");
        assertThat(init.get("colorIndex").asInt()).isEqualTo(1);
        assertThat(init.get("seed").path("title").isMissingNode()).isFalse();
        assertThat(init.get("values").isObject()).isTrue();
        assertThat(init.get("peers").isArray()).isTrue();
        assertThat(init.get("sessionId").asString()).matches("^[0-9a-f]{32}$");
        assertThat(init.get("responseId").isNull()).isTrue();

        Socket ben = connect("/ws/rooms/relay-a?name=Ben");
        JsonNode benInit = ben.next();
        assertThat(benInit.get("sessionId").asString()).isEqualTo(init.get("sessionId").asString());
        assertThat(benInit.get("clientId").asString()).isNotEqualTo(init.get("clientId").asString());

        ann.send("{\"type\":\"value\",\"key\":\"q1\",\"value\":{\"d\":{},\"g\":1.0}}");
        assertThat(ben.next("value")).isEqualTo(json("{\"type\":\"value\",\"from\":\"" + init.get("clientId").asString()
                + "\",\"key\":\"q1\",\"value\":{\"d\":{},\"g\":1.0}}"));
        ann.send("{\"type\":\"presence\",\"state\":{\"focus\":\"q1\"},\"retain\":true}");
        JsonNode peer = ben.next("peer");
        assertThat(peer.get("retain").asBoolean()).isTrue();
        assertThat(peer.get("peer").get("clientId").asString()).isEqualTo(init.get("clientId").asString());
        assertThat(peer.get("peer").get("name").asString()).isEqualTo("Ann");
        ann.send("not json");
        ann.send("{\"type\":\"nonsense\"}");

        Socket cat = connect("/ws/rooms/relay-a?name=Cat");
        JsonNode late = cat.next();
        assertThat(late.get("values").get("q1")).isEqualTo(json("{\"d\":{},\"g\":1.0}"));
        assertThat(late.get("peers").size()).isEqualTo(1);
        assertThat(late.get("peers").get(0).get("clientId").asString()).isEqualTo(init.get("clientId").asString());
        ann.quiet(200);   // no echo of its own messages, nothing for the junk

        cat.close();
        assertThat(ann.next("peer-left").get("clientId").asString()).isEqualTo(late.get("clientId").asString());
    }

    @Test
    void twoCompletesAtOnceSaveOneRowForEveryone() throws Exception {
        int before = jobSheets();
        Socket ann = connect("/ws/rooms/relay-b?name=Ann");
        Socket ben = connect("/ws/rooms/relay-b?name=Ben");
        ann.next("init");
        ben.next("init");
        String data = "{\"q1\":\"done\",\"d\":{},\"g\":1.5,\"h\":\"naïve — 東京\"}";
        ann.send("{\"type\":\"complete\",\"data\":" + data + "}");
        ben.send("{\"type\":\"complete\",\"data\":" + data + "}");
        JsonNode first = ann.next("completed");
        JsonNode second = ann.next("completed");
        JsonNode bens = ben.next("completed");
        JsonNode bens2 = ben.next("completed");
        assertThat(first.get("id").isInt()).isTrue();
        assertThat(List.of(second.get("id"), bens.get("id"), bens2.get("id"))).allMatch(id -> id.equals(first.get("id")));
        assertThat(first.get("data")).isEqualTo(json(data));
        assertThat(jobSheets()).isEqualTo(before + 1);
        assertThat(json(jdbc.sql("SELECT data FROM responses WHERE id = ?").param(first.get("id").asInt()).query(String.class).single()))
                .isEqualTo(json(data));

        ann.send("{\"type\":\"value\",\"key\":\"q1\",\"value\":\"changed after completing\"}");   // ignored once saved
        Socket cat = connect("/ws/rooms/relay-b?name=Cat");
        JsonNode late = cat.next("init");
        assertThat(late.get("responseId")).isEqualTo(first.get("id"));
        assertThat(late.get("values").get("q1").asString()).isEqualTo("done");
        assertThat(jobSheets()).isEqualTo(before + 1);
    }

    @Test
    void theFinishedSheetIsStoredAsSent() throws Exception {
        Socket ann = connect("/ws/rooms/roundtrip?name=Ann");
        ann.next("init");
        ann.send("{\"type\":\"complete\",\"data\":" + ROUNDTRIP_TEXT + "}");
        int id = ann.next("completed").get("id").asInt();
        assertThat(json(jdbc.sql("SELECT data FROM responses WHERE id = ? AND form_id = 'job-sheet'").param(id).query(String.class).single()))
                .isEqualTo(roundtrip());
    }

    @Test
    void aRoomIsSeededFromTheSavedRecord() throws Exception {
        var row = jdbc.sql("SELECT id, data FROM responses WHERE form_id = 'job-sheet' ORDER BY id LIMIT 1").query().singleRow();
        Socket ws = connect("/ws/rooms/" + row.get("id"));
        JsonNode init = ws.next("init");
        assertThat(init.get("values")).isEqualTo(json((String) row.get("data")));
        assertThat(init.get("name").asString()).isEqualTo("Alice Martin");
    }

    // ---------- edit-together

    @Test
    void editRecordsReachLateJoinersInOrderAndPresenceSyncFollowsInit() throws Exception {
        Socket ann = connect("/ws/forms/relay-c?name=Ann");
        JsonNode init = ann.next();
        assertThat(init.propertyNames()).containsExactlyInAnyOrder("type", "clientId", "colorIndex", "color", "seed", "log");
        assertThat(init.get("type").asString()).isEqualTo("init");
        assertThat(init.get("log").isEmpty()).isTrue();
        ann.send("{\"type\":\"presence\",\"state\":{\"tab\":\"designer\"}}");

        Socket ben = connect("/ws/forms/relay-c?name=Ben");
        assertThat(ben.next().get("type").asString()).isEqualTo("init");
        JsonNode sync = ben.next();
        assertThat(sync.get("type").asString()).isEqualTo("presence-sync");
        assertThat(sync.get("peers").get(0).get("clientId").asString()).isEqualTo(init.get("clientId").asString());

        List<String> records = List.of("{\"seq\":1,\"op\":0,\"payload\":{\"name\":\"title\",\"value\":\"One\"}}", "{\"seq\":1,\"op\":0,\"payload\":{}}");
        for (String record : records) {
            ann.send("{\"type\":\"append\",\"payload\":" + record + "}");
        }
        for (String record : records) {
            JsonNode relayed = ben.next("record");
            assertThat(relayed.get("payload")).isEqualTo(json(record));
            assertThat(relayed.get("from").asString()).isEqualTo(init.get("clientId").asString());
        }
        Socket cat = connect("/ws/forms/relay-c?name=Cat");
        assertThat(cat.next("init").get("log")).isEqualTo(json("[" + String.join(",", records) + "]"));
        cat.close();
        assertThat(ann.next("presence-leave").get("clientId").isString()).isTrue();
    }

    @Test
    void theEditRoomIsSeededWithTheSavedDefinition() throws Exception {
        String saved = jdbc.sql("SELECT json FROM forms WHERE key = 'support-team'").query(String.class).single();
        Socket ws = connect("/ws/forms/support-team");
        assertThat(ws.next("init").get("seed")).isEqualTo(json(saved));
    }

    @Test
    void aOneMegabyteFrameIsAccepted() throws Exception {
        Socket ann = connect("/ws/forms/relay-big?name=Ann");
        Socket ben = connect("/ws/forms/relay-big?name=Ben");
        ann.next("init");
        ben.next("init");
        String blob = "x".repeat(1024 * 1024);
        ann.send("{\"type\":\"append\",\"payload\":{\"blob\":\"" + blob + "\"}}");
        assertThat(ben.next("record").get("payload").get("blob").asString()).hasSize(blob.length());
        // and a finished job sheet of the same size
        Socket fill = connect("/ws/rooms/relay-big?name=Ann");
        fill.next("init");
        fill.send("{\"type\":\"complete\",\"data\":{\"notes\":\"" + blob + "\"}}");
        assertThat(fill.next("completed").get("data").get("notes").asString()).hasSize(blob.length());
    }

    @Test
    void refusedHandshakes() {
        assertRefused("/ws/rooms/x", null);
        assertRefused("/ws/rooms/x", "demo_user=none");
        assertRefused("/ws/rooms/x", "demo_user=mallory");
        assertRefused("/ws/forms/x", null);
        assertRefused("/ws/forms/x", "demo_user=bob");
        assertRefused("/ws/rooms/bad%20id", "demo_user=alice");
        assertRefused("/ws/forms/" + "x".repeat(65), "demo_user=alice");
    }

    @Test
    void bobMayFillTogether() throws Exception {
        Socket bob = connect("/ws/rooms/relay-bob", "demo_user=bob");
        assertThat(bob.next("init").get("name").asString()).isEqualTo("Bob Fischer");
    }

    /** Demo mode: room keys are <demo_sid>:<id>, so visitors in different sandboxes never share a room. */
    @Nested
    @TestPropertySource(properties = "surveyjs.demo-mode=true")
    class DemoMode {

        @Autowired
        SurveyJsProperties settings;

        @Autowired
        RoomKey roomKey;

        @LocalServerPort
        int port;

        String sandbox() throws Exception {
            HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/demo/stored/fill-together")).build(),
                    HttpResponse.BodyHandlers.ofString());
            return cookieValue(setCookie(r.headers().allValues("Set-Cookie"), "demo_sid"));
        }

        Socket join(String sid) throws Exception {
            Socket socket = new Socket();
            WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
            headers.add("Cookie", "demo_user=alice; demo_sid=" + sid);
            socket.session = CLIENT.execute(socket, headers, URI.create("ws://127.0.0.1:" + port + "/ws/rooms/shared")).get(10, TimeUnit.SECONDS);
            open.add(socket);
            return socket;
        }

        @Test
        void sandboxesGetTheirOwnRoomsAndSnapshots() throws Exception {
            String mine = sandbox();
            String theirs = sandbox();
            assertThat(mine).isNotEqualTo(theirs);
            assertThatThrownBy(() -> {   // no demo_sid, no sandbox: refused
                WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
                headers.add("Cookie", "demo_user=alice");
                CLIENT.execute(new TextWebSocketHandler(), headers, URI.create("ws://127.0.0.1:" + port + "/ws/rooms/shared")).get(10, TimeUnit.SECONDS);
            }).isInstanceOf(ExecutionException.class);

            Socket first = join(mine);
            first.next("init");
            Socket same = join(mine);
            same.next("init");
            first.send("{\"type\":\"value\",\"key\":\"q1\",\"value\":\"mine\"}");
            assertThat(same.next("value").get("value").asString()).isEqualTo("mine");   // same sandbox: same room
            Socket late = join(mine);
            assertThat(late.next("init").get("values").get("q1").asString()).isEqualTo("mine");
            Socket other = join(theirs);
            JsonNode otherInit = other.next("init");
            assertThat(otherInit.get("values").has("q1")).isFalse();   // another sandbox: another room
            first.quiet(200);   // the other sandbox's join isn't seen here

            Path snapshot = roomKey.snapshotPath(RoomKey.of(mine, "shared"), "fill");
            assertThat(snapshot.getFileName().toString()).matches("^[0-9a-f]{64}\\.json$");
            assertThat(snapshot.getParent()).isEqualTo(settings.relayDir());
            assertThat(Files.isRegularFile(snapshot)).isTrue();
            HttpResponse<String> room = HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/demo/stored/fill-together?room=shared"))
                    .header("Cookie", "demo_user=alice; demo_sid=" + mine).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(json(room.body()).get("relay room").get("values")).isEqualTo(json("{\"q1\":\"mine\"}"));
        }
    }
}
