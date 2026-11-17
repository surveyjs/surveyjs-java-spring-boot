package io.surveyjs.security;

import io.surveyjs.integration.FileStore.StoredFile;

/**
 * The checks the examples call besides the {@code edit-forms} authority. Your real permission checks go here.
 */
public final class Permissions {

    private Permissions() {
    }

    /**
     * May this user read this uploaded file (store-files)? Any signed-in user may here; real apps check ownership,
     * for example {@code file.ownerId() == user.id()}. A denial answers 404, so it doesn't confirm the file exists.
     */
    public static boolean canReadFile(User user, StoredFile file) {
        return user != null && file != null;
    }
}
