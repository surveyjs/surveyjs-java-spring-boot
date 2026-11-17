package io.surveyjs.examples;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import io.surveyjs.integration.FileStore;
import io.surveyjs.integration.FileStore.StoredFile;
import io.surveyjs.integration.FileType;
import io.surveyjs.security.Permissions;
import io.surveyjs.security.User;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

/**
 * Store Files Separately: POST /api/files stores uploads, GET /api/files/{id} serves them after an access check.
 *
 * <p>Creator's uploads (creator-uploads) post to the same endpoint.
 */
@RestController
public class StoreFilesController {

    private static final long MAX_BYTES = 5L * 1024 * 1024;

    private final JdbcClient jdbc;
    private final FileStore fileStore;

    public StoreFilesController(JdbcClient jdbc, FileStore fileStore) {
        this.jdbc = jdbc;
        this.fileStore = fileStore;
    }

    // #region sjs:store-files.server
    // POST /api/files — store uploads, return their ids in the same order
    @PostMapping(path = "/api/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public List<String> storeFiles(MultipartHttpServletRequest request) throws IOException {
        List<MultipartFile> files = request.getFiles("files[]");   // the name on every platform: PHP keeps only the last of several "files"
        if (files.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "Upload one or more files in a field named \"files[]\"");
        }
        for (MultipartFile file : files) {   // check the whole batch before storing any
            if (file.getSize() > MAX_BYTES) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "Each file must be 5 MB or less");
            }
            if (FileType.detect(file) == null) {   // PNG, JPEG, WebP, GIF or PDF by the bytes, never the client's Content-Type
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, file.getOriginalFilename() + " is not a PNG, JPEG, WebP, GIF or PDF file");
            }
        }
        List<String> ids = new ArrayList<>();
        for (MultipartFile file : files) {
            String id = UUID.randomUUID().toString();   // named by id, never by the client
            fileStore.write(id, file);
            jdbc.sql("INSERT INTO files (id, name, type) VALUES (?, ?, ?)").params(id, file.getOriginalFilename(), FileType.detect(file)).update();
            ids.add(id);
        }
        return ids;
    }
    // #endregion

    // #region sjs:store-files.download
    // GET /api/files/{id} — the content, for private files only; check access first
    @GetMapping("/api/files/{id}")
    public ResponseEntity<Resource> downloadFile(@PathVariable String id, @AuthenticationPrincipal User user) {
        StoredFile file = jdbc.sql("SELECT id, name, type FROM files WHERE id = ?").param(id).query(StoredFile.class).optional().orElse(null);
        if (file == null || !Permissions.canReadFile(user, file)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);   // 404, not 403: don't confirm the file exists
        }

        // nosniff and sandbox: an uploaded HTML or SVG never runs as this site
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(file.type()))
                .header("X-Content-Type-Options", "nosniff").header("Content-Security-Policy", "sandbox")
                .body(fileStore.read(file));
    }
    // #endregion
}
