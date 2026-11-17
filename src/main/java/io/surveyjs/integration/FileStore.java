package io.surveyjs.integration;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import io.surveyjs.config.SurveyJsProperties;
import io.surveyjs.demo.SandboxContext;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

/**
 * Uploaded files on disk, named by their id, never by the client's file name (store-files, creator-uploads). The
 * files table keeps the name and the detected type. In demo mode each visitor sandbox has its own folder.
 */
@Component
public class FileStore {

    /** A row of the files table. */
    public record StoredFile(String id, String name, String type) {
    }

    private final SurveyJsProperties settings;

    public FileStore(SurveyJsProperties settings) {
        this.settings = settings;
    }

    /** Writes an upload's bytes under its id. */
    public void write(String id, MultipartFile file) throws IOException {
        Path target = folder().resolve(id);
        Files.createDirectories(target.getParent());
        try (InputStream in = file.getInputStream()) {
            Files.copy(in, target);
        }
    }

    /** The stored bytes of a file, to send back. */
    public FileSystemResource read(StoredFile file) {
        return new FileSystemResource(folder().resolve(file.id()));
    }

    /** The request's uploads folder: the app's, or (demo mode) the visitor sandbox's. */
    private Path folder() {
        String sandbox = SandboxContext.id();
        return sandbox == null ? settings.uploadsDir() : settings.sandboxDir().resolve(sandbox).resolve("uploads");
    }
}
