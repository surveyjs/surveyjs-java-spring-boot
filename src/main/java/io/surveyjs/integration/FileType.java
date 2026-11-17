package io.surveyjs.integration;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.springframework.web.multipart.MultipartFile;

/**
 * The type of an upload by its first bytes, never by the client's Content-Type or file name: PNG, JPEG, GIF, WebP
 * or PDF, the formats SurveyJS Server's isSupportedDocument() accepts (it finds %PDF in the first 4 KB, as here).
 * Used by store-files and extract-from-paper. Never trust {@code MultipartFile.getContentType()}: the client sets it.
 */
public final class FileType {

    private static final int HEAD = 4096;

    private FileType() {
    }

    /** "image/png", "image/jpeg", "image/gif", "image/webp", "application/pdf", or null for anything else. */
    public static String detect(MultipartFile file) throws IOException {
        try (InputStream in = file.getInputStream()) {
            return detect(in.readNBytes(HEAD));
        }
    }

    public static String detect(byte[] bytes) {
        byte[] head = bytes.length > HEAD ? Arrays.copyOf(bytes, HEAD) : bytes;
        if (startsWith(head, new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'})) {
            return "image/png";
        }
        if (startsWith(head, new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff})) {
            return "image/jpeg";
        }
        if (startsWith(head, ascii("GIF87a")) || startsWith(head, ascii("GIF89a"))) {
            return "image/gif";
        }
        if (startsWith(head, ascii("RIFF")) && head.length >= 12 && Arrays.equals(head, 8, 12, ascii("WEBP"), 0, 4)) {
            return "image/webp";
        }
        if (new String(head, StandardCharsets.ISO_8859_1).contains("%PDF")) {
            return "application/pdf";
        }
        return null;
    }

    private static boolean startsWith(byte[] head, byte[] prefix) {
        return head.length >= prefix.length && Arrays.equals(head, 0, prefix.length, prefix, 0, prefix.length);
    }

    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }
}
