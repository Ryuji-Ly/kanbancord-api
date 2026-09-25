package com.kanbancord_api.sync;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Checks the bot's token against the SHA-256 hash the API is configured with, in constant time.
 * Shared by the internal sync endpoints and by requests the bot makes on behalf of a user.
 */
public final class InternalBotToken {

    private InternalBotToken() {
    }

    /** Whether hashes are configured and {@code provided} matches; a malformed hash never matches. */
    public static boolean matches(String expectedSha256Hex, String provided) {
        if (expectedSha256Hex == null || provided == null || provided.isBlank()) {
            return false;
        }
        String normalized = expectedSha256Hex.trim();
        if (!normalized.matches("(?i)^[0-9a-f]{64}$")) {
            return false;
        }
        byte[] expected = new byte[32];
        for (int index = 0; index < normalized.length(); index += 2) {
            expected[index / 2] = (byte) Integer.parseInt(normalized.substring(index, index + 2), 16);
        }
        return MessageDigest.isEqual(expected, sha256(provided));
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 algorithm is not available", ex);
        }
    }
}
