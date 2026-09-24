package com.kanbancord_api.security;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TokenCipherTest {

    private static final String JWT_SECRET = "a-jwt-secret-that-is-at-least-32-bytes-long";

    private static TokenCipher cipher(String configuredKey, String jwtSecret) {
        AuthJwtProperties properties = new AuthJwtProperties();
        properties.setSecret(jwtSecret);
        return new TokenCipher(configuredKey, properties);
    }

    @Test
    void roundTrips_andNeverStoresThePlaintext() {
        TokenCipher cipher = cipher("", JWT_SECRET);
        byte[] sealed = cipher.encrypt("discord-token", "owner:1");

        assertEquals("discord-token", cipher.decrypt(sealed, "owner:1"));
        assertFalse(new String(sealed).contains("discord-token"));
        assertFalse(java.util.Arrays.equals(sealed, cipher.encrypt("discord-token", "owner:1")),
                "each encryption uses a fresh IV");
    }

    @Test
    void valuesAreBoundToTheirOwner_andToTheKey() {
        TokenCipher cipher = cipher("", JWT_SECRET);
        byte[] sealed = cipher.encrypt("discord-token", "owner:1");

        assertThrows(TokenCipher.UndecryptableTokenException.class, () -> cipher.decrypt(sealed, "owner:2"));
        assertThrows(TokenCipher.UndecryptableTokenException.class,
                () -> cipher("", JWT_SECRET + "-rotated").decrypt(sealed, "owner:1"));

        byte[] tampered = sealed.clone();
        tampered[tampered.length - 1] ^= 1;
        assertThrows(TokenCipher.UndecryptableTokenException.class, () -> cipher.decrypt(tampered, "owner:1"));
    }

    @Test
    void aConfiguredKey_takesPrecedence_andMustBe32Bytes() {
        String key = Base64.getEncoder().encodeToString(new byte[32]);
        byte[] sealed = cipher(key, JWT_SECRET).encrypt("t", "o");

        assertEquals("t", cipher(key, "another-jwt-secret-that-is-at-least-32-bytes").decrypt(sealed, "o"));
        assertArrayEquals(new byte[] {1}, new byte[] {sealed[0]}, "values carry a format version");
        assertThrows(IllegalStateException.class,
                () -> cipher(Base64.getEncoder().encodeToString(new byte[16]), JWT_SECRET));
    }
}
