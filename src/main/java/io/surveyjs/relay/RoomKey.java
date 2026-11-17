package io.surveyjs.relay;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

import io.surveyjs.config.SurveyJsProperties;
import io.surveyjs.demo.SandboxContext;
import io.surveyjs.integration.StoredJson;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Demo only: in demo mode a room belongs to one visitor sandbox, so its key is {@code <demo_sid>:<id>}, and visitors in
 * different sandboxes never share a room. Outside demo mode the key is the plain record or form id. The relays get
 * their key here, outside their regions; the Data panel names snapshots with the same helper.
 */
@Component
public class RoomKey {

    private static final Log log = LogFactory.getLog(RoomKey.class);

    private final SurveyJsProperties settings;
    private final JsonMapper json;

    public RoomKey(SurveyJsProperties settings, JsonMapper json) {
        this.settings = settings;
        this.json = json;
    }

    /** The room key for a record or form id, in a sandbox (demo mode) or not. */
    public static String of(String sandboxId, String id) {
        return sandboxId == null ? id : sandboxId + ":" + id;
    }

    /** The current connection's room key, or null in demo mode without a sandbox (the relay then refuses it). */
    public String forId(String id) {
        if (!settings.demoMode()) {
            return id;
        }
        String sandboxId = SandboxContext.id();
        return sandboxId == null ? null : of(sandboxId, id);
    }

    /**
     * Where a relay writes a room snapshot for the Data panel (demo/StoredController reads it), or null outside demo
     * mode. Room keys contain ":" (invalid in Windows file names) and ids from the URL, so the file is named by the
     * key's SHA-256.
     */
    public Path snapshotPath(String key, String relay) {
        if (!settings.demoMode()) {
            return null;
        }
        String name = sha256(key) + ".json";
        return settings.relayDir().resolve(relay.equals("fill") ? name : relay + "-" + name);
    }

    /** Writes a snapshot with its time. A failure is logged, never thrown: the relay keeps relaying. */
    public void writeSnapshot(Path path, Map<String, Object> snapshot) {
        if (path == null) {
            return;
        }
        Map<String, Object> stamped = new LinkedHashMap<>(snapshot);
        stamped.put("updatedAt", StoredJson.timestamp());
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, json.writer(SerializationFeature.INDENT_OUTPUT).writeValueAsString(stamped), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Couldn't write the relay snapshot " + path.getFileName() + ": " + e.getMessage());
        }
    }

    static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
