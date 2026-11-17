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

/**
 * Edit Forms Together rooms: the saved definition a room started from, every Creator record since (in arrival order)
 * and the people in it. examples/EditTogetherRelay runs the relay; this class holds a room, and {@link Rooms} every
 * open room.
 */
public final class EditRoom {

    /** Colour slots, skipping 5 (yellow is unreadable with white text); 0 is gray and never assigned. */
    private static final int[] SLOTS = {1, 2, 3, 4, 6, 7, 8, 9};

    /** The colour of each slot: the same values as survey-core's --sjs2-color-utility-user-bg-color-N. */
    private static final String[] PALETTE = {"#808080", "#1570EF", "#CA4FFB", "#19B35C", "#19B394", "#F9C50B", "#F99130", "#F1529C", "#02ADEB", "#4E6198"};

    private final ReentrantLock lock = new ReentrantLock();
    private final JsonNode seed;
    private final List<JsonNode> log = new ArrayList<>();   // append-only: never deduplicated, replayed in this order
    private final List<Participant> clients = new ArrayList<>();
    private final JsonMapper json;
    private final RoomKey snapshots;
    private final Path snapshot;
    private ScheduledFuture<?> drop;

    EditRoom(JsonNode seed, JsonMapper json, RoomKey snapshots, Path snapshot) {
        this.seed = seed;
        this.json = json;
        this.snapshots = snapshots;
        this.snapshot = snapshot;
    }

    public ReentrantLock lock() {
        return lock;
    }

    public JsonNode seed() {
        return seed;
    }

    public List<JsonNode> log() {
        return log;
    }

    /** A new participant, on the first colour slot nobody in the room holds. */
    public Participant add(WebSocketSession session, String name) {
        lock.lock();
        try {
            int slot = SLOTS[clients.size() % SLOTS.length];
            for (int candidate : SLOTS) {
                if (clients.stream().noneMatch(c -> c.slot() == candidate)) {
                    slot = candidate;
                    break;
                }
            }
            Participant client = new Participant(session, json, name, slot);
            clients.add(client);
            changed();
            return client;
        } finally {
            lock.unlock();
        }
    }

    public String color(Participant client) {
        return PALETTE[client.slot()];
    }

    /** Creator's plugin paints rings and cursors from {@code color}, so each peer carries it next to colorIndex. */
    public Map<String, Object> peer(Participant client) {
        return Participant.message("clientId", client.id(), "name", client.name(), "colorIndex", client.slot(), "color", color(client),
                "state", client.state());
    }

    /** Everyone else who has shared their presence. */
    public List<Map<String, Object>> roster(Participant otherThan) {
        return clients.stream().filter(c -> c != otherThan && c.state() != null).map(this::peer).toList();
    }

    /** A Creator record: pushed to the log and relayed to the others, in arrival order. */
    public void append(Participant sender, JsonNode payload) {
        lock.lock();
        try {
            log.add(payload);
            broadcast(sender, Participant.message("type", "record", "from", sender.id(), "payload", payload));
        } finally {
            lock.unlock();
        }
    }

    public void presence(Participant sender, JsonNode state) {
        lock.lock();
        try {
            sender.state(state);
            broadcast(sender, Participant.message("type", "presence", "peer", peer(sender)));
        } finally {
            lock.unlock();
        }
    }

    /** Sends to everyone in the room except the sender. */
    public void broadcast(Participant sender, Map<String, Object> message) {
        lock.lock();
        try {
            for (Participant client : clients) {
                if (client != sender) {
                    client.send(message);
                }
            }
            if (!"presence".equals(message.get("type"))) {
                changed();
            }
        } finally {
            lock.unlock();
        }
    }

    /** Demo only: the room's state for the Data panel (DEMO_MODE=true). */
    private void changed() {
        JsonNode title = seed.path("title");
        snapshots.writeSnapshot(snapshot, Participant.message("participants", clients.size(),
                "seed title", title.isMissingNode() ? null : title, "records in the log", log.size()));
    }

    /** Every open edit-together room, by room key. */
    @Component
    public static class Rooms {

        private final Map<String, EditRoom> rooms = new ConcurrentHashMap<>();
        private final JsonMapper json;
        private final RoomKey roomKey;
        private final TaskScheduler scheduler;

        public Rooms(JsonMapper json, RoomKey roomKey, TaskScheduler taskScheduler) {
            this.json = json;
            this.roomKey = roomKey;
            this.scheduler = taskScheduler;
        }

        /** The open room, or a new one seeded with the saved definition. */
        public EditRoom join(String key, Supplier<JsonNode> seed) {
            EditRoom room = rooms.computeIfAbsent(key, k -> new EditRoom(seed.get(), json, roomKey, roomKey.snapshotPath(k, "edit")));
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
        public EditRoom room(String key) {
            return rooms.get(key);
        }

        /** Tells the others; the log lives until the room empties, and 2 s more. */
        public void leave(String key, Participant client) {
            EditRoom room = rooms.get(key);
            if (room == null || client == null) {
                return;
            }
            room.lock.lock();
            try {
                if (!room.clients.remove(client)) {
                    return;
                }
                room.broadcast(client, Participant.message("type", "presence-leave", "clientId", client.id()));
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
