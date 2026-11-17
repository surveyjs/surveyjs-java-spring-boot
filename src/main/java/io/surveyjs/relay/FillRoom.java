package io.surveyjs.relay;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Fill Forms Together rooms: the definition a room hands out, the latest value of every answer and the people in it.
 * examples/FillTogetherRelay runs the relay; this class holds a room, and {@link Rooms} every open room. Each room
 * has a {@link ReentrantLock} (not {@code synchronized}, which pins virtual threads on Java 21): the relay's handlers
 * run on many threads at once.
 */
public final class FillRoom {

    private static final int SLOW_PEER_BACKLOG = 1024 * 1024;   // queued bytes: a cursor move may be dropped for a peer this far behind

    private final ReentrantLock lock = new ReentrantLock();
    private final JsonNode seed;
    private ObjectNode values;                                 // question key => latest value; last write wins
    private final List<Participant> clients = new ArrayList<>();
    private Integer responseId;                                // the response saved in this room session, once someone completes
    private final String sessionId = Participant.hex(16);      // 32 random hex characters per room instance
    private final JsonMapper json;
    private final RoomKey snapshots;
    private final Path snapshot;
    private ScheduledFuture<?> drop;

    FillRoom(JsonNode seed, ObjectNode values, JsonMapper json, RoomKey snapshots, Path snapshot) {
        this.seed = seed;
        this.values = values.deepCopy();
        this.json = json;
        this.snapshots = snapshots;
        this.snapshot = snapshot;
    }

    /** One room operation at a time: two completes at once save once. */
    public ReentrantLock lock() {
        return lock;
    }

    public JsonNode seed() {
        return seed;
    }

    public ObjectNode values() {
        return values;
    }

    public Integer responseId() {
        return responseId;
    }

    public String sessionId() {
        return sessionId;
    }

    /** A new participant, on the lowest colour slot nobody in the room holds, from 1. */
    public Participant add(WebSocketSession session, String name) {
        lock.lock();
        try {
            int slot = 1;
            while (taken(slot)) {
                slot++;
            }
            Participant client = new Participant(session, json, name, slot);
            clients.add(client);
            changed();
            return client;
        } finally {
            lock.unlock();
        }
    }

    private boolean taken(int slot) {
        return clients.stream().anyMatch(c -> c.slot() == slot);
    }

    /** Shown as 1..9 (0 means "unknown"). */
    public int colorIndex(Participant client) {
        return 1 + (client.slot() - 1) % 9;
    }

    public Map<String, Object> peer(Participant client, JsonNode state) {
        return Participant.message("clientId", client.id(), "name", client.name(), "colorIndex", colorIndex(client), "state", state);
    }

    /** Everyone else who has shared a retained presence. */
    public List<Map<String, Object>> roster(Participant otherThan) {
        return clients.stream().filter(c -> c != otherThan && c.state() != null).map(c -> peer(c, c.state())).toList();
    }

    /** The latest value for a key, relayed to the others. Ignored once the room's response is saved. */
    public void value(Participant sender, String key, JsonNode value) {
        lock.lock();
        try {
            if (responseId != null) {
                return;
            }
            values.set(key, value);   // last write wins
            broadcast(sender, Participant.message("type", "value", "from", sender.id(), "key", key, "value", value), false);
        } finally {
            lock.unlock();
        }
    }

    /** A presence update, stamped with the sender's name and colour. A retained one is kept for late joiners. */
    public void presence(Participant sender, JsonNode state, boolean retain) {
        lock.lock();
        try {
            if (retain) {
                sender.state(state);
            }
            broadcast(sender, Participant.message("type", "peer", "retain", retain, "peer", peer(sender, state)), !retain);
        } finally {
            lock.unlock();
        }
    }

    /** The room's response was saved: its answers become the room's values. */
    public void saved(int id, ObjectNode data) {
        responseId = id;
        values = data.deepCopy();
    }

    /** Sends to everyone in the room except the sender: nobody gets their own echo. A droppable message (a cursor
     *  move) is skipped for a peer that is falling behind. */
    public void broadcast(Participant sender, Map<String, Object> message, boolean droppable) {
        lock.lock();
        try {
            for (Participant client : clients) {
                if (client != sender && !(droppable && client.backlog() > SLOW_PEER_BACKLOG)) {
                    client.send(message);
                }
            }
            if (!"peer".equals(message.get("type"))) {
                changed();
            }
        } finally {
            lock.unlock();
        }
    }

    public void sendAll(Map<String, Object> message) {
        lock.lock();
        try {
            clients.forEach(client -> client.send(message));
            changed();
        } finally {
            lock.unlock();
        }
    }

    /** Demo only: the room's state for the Data panel (DEMO_MODE=true). */
    private void changed() {
        snapshots.writeSnapshot(snapshot, Participant.message("participants", clients.size(), "values", values, "responseId", responseId));
    }

    /** Every open fill-together room, by room key. */
    @Component
    public static class Rooms {

        private final Map<String, FillRoom> rooms = new ConcurrentHashMap<>();
        private final JsonMapper json;
        private final RoomKey roomKey;
        private final TaskScheduler scheduler;

        public Rooms(JsonMapper json, RoomKey roomKey, TaskScheduler taskScheduler) {
            this.json = json;
            this.roomKey = roomKey;
            this.scheduler = taskScheduler;
        }

        /** The seed and the saved answers of a new room. */
        public record Seed(JsonNode definition, ObjectNode values) {
        }

        /** The open room, or a new one seeded with the definition and the saved answers. */
        public FillRoom join(String key, Supplier<Seed> seed) {
            FillRoom room = rooms.computeIfAbsent(key, k -> {
                Seed s = seed.get();
                return new FillRoom(s.definition(), s.values(), json, roomKey, roomKey.snapshotPath(k, "fill"));
            });
            room.lock.lock();
            try {
                if (room.drop != null) {   // back within 2 s: keep the room
                    room.drop.cancel(false);
                    room.drop = null;
                }
            } finally {
                room.lock.unlock();
            }
            return room;
        }

        /** The open room of a connected participant. */
        public FillRoom room(String key) {
            return rooms.get(key);
        }

        /** Tells the others, and drops the room 2 s after the last person leaves. */
        public void leave(String key, Participant client) {
            FillRoom room = rooms.get(key);
            if (room == null || client == null) {
                return;
            }
            room.lock.lock();
            try {
                if (!room.clients.remove(client)) {
                    return;
                }
                room.broadcast(client, Participant.message("type", "peer-left", "clientId", client.id()), false);
                if (room.clients.isEmpty()) {
                    room.drop = scheduler.schedule(() -> rooms.computeIfPresent(key, (k, r) -> r == room && r.clients.isEmpty() ? null : r),
                            Instant.now().plusSeconds(2));
                }
            } finally {
                room.lock.unlock();
            }
        }
    }
}
