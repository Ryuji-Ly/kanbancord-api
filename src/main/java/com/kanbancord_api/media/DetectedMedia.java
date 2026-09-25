package com.kanbancord_api.media;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;

/**
 * What a file really is, read from its first bytes rather than trusted from its name or the type the
 * browser claims. Only the formats Imgur hosts and browsers play are recognised.
 */
public record DetectedMedia(Kind kind, String contentType) {

    public enum Kind {
        IMAGE,
        VIDEO
    }

    /** How many leading bytes {@link #detect} needs. */
    public static final int HEADER_BYTES = 16;

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] WEBM = {0x1A, 0x45, (byte) 0xDF, (byte) 0xA3};

    public static Optional<DetectedMedia> detect(byte[] header) {
        if (startsWith(header, 0, PNG)) {
            return Optional.of(new DetectedMedia(Kind.IMAGE, "image/png"));
        }
        if (startsWith(header, 0, JPEG)) {
            return Optional.of(new DetectedMedia(Kind.IMAGE, "image/jpeg"));
        }
        if (startsWith(header, 0, ascii("GIF87a")) || startsWith(header, 0, ascii("GIF89a"))) {
            return Optional.of(new DetectedMedia(Kind.IMAGE, "image/gif"));
        }
        if (startsWith(header, 0, ascii("RIFF")) && startsWith(header, 8, ascii("WEBP"))) {
            return Optional.of(new DetectedMedia(Kind.IMAGE, "image/webp"));
        }
        if (startsWith(header, 4, ascii("ftyp"))) {
            // ISO media: QuickTime files name the "qt  " brand, everything else plays as MP4.
            return Optional.of(new DetectedMedia(Kind.VIDEO,
                    startsWith(header, 8, ascii("qt  ")) ? "video/quicktime" : "video/mp4"));
        }
        if (startsWith(header, 0, WEBM)) {
            return Optional.of(new DetectedMedia(Kind.VIDEO, "video/webm"));
        }
        return Optional.empty();
    }

    private static boolean startsWith(byte[] data, int offset, byte[] prefix) {
        return data.length >= offset + prefix.length
                && Arrays.equals(data, offset, offset + prefix.length, prefix, 0, prefix.length);
    }

    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }
}
