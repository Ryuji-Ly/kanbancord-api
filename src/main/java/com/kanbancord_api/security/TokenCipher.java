package com.kanbancord_api.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Encrypts third-party tokens at rest with AES-256-GCM.
 *
 * <p>The key comes from {@code kanbancord.auth.token-encryption-key} (32 bytes, base64). Without it,
 * a key is derived from the JWT secret, so changing that secret also makes stored tokens unreadable
 * and their users sign in to Discord again. Each value is bound to its owner through the associated
 * data, so a ciphertext copied to another row does not decrypt.
 */
@Component
public class TokenCipher {

    private static final Logger log = LoggerFactory.getLogger(TokenCipher.class);
    private static final byte VERSION = 1;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final String DERIVATION_LABEL = "kanbancord/token-encryption/v1";

    private final SecretKey key;
    private final SecureRandom secureRandom = new SecureRandom();

    public TokenCipher(
            @Value("${kanbancord.auth.token-encryption-key:}") String configuredKey,
            AuthJwtProperties authJwtProperties) {
        this.key = resolveKey(configuredKey, authJwtProperties.getSecret());
    }

    private static SecretKey resolveKey(String configuredKey, String jwtSecret) {
        if (configuredKey != null && !configuredKey.isBlank()) {
            byte[] bytes = Base64.getDecoder().decode(configuredKey.trim());
            if (bytes.length != 32) {
                throw new IllegalStateException("kanbancord.auth.token-encryption-key must be 32 bytes, base64-encoded");
            }
            return new SecretKeySpec(bytes, "AES");
        }
        if (jwtSecret == null || jwtSecret.isBlank()) {
            return null;
        }
        log.info("kanbancord.auth.token-encryption-key is not set; deriving the token encryption key from the JWT secret");
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(jwtSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return new SecretKeySpec(mac.doFinal(DERIVATION_LABEL.getBytes(StandardCharsets.UTF_8)), "AES");
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Failed to derive the token encryption key", ex);
        }
    }

    /** The value was not encrypted with the current key, or has been tampered with. */
    public static class UndecryptableTokenException extends RuntimeException {
        public UndecryptableTokenException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public byte[] encrypt(String plaintext, String owner) {
        byte[] iv = new byte[IV_BYTES];
        secureRandom.nextBytes(iv);
        try {
            Cipher cipher = cipher(Cipher.ENCRYPT_MODE, iv, owner);
            byte[] sealed = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.allocate(1 + IV_BYTES + sealed.length).put(VERSION).put(iv).put(sealed).array();
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Failed to encrypt token", ex);
        }
    }

    public String decrypt(byte[] ciphertext, String owner) {
        if (ciphertext == null || ciphertext.length <= 1 + IV_BYTES || ciphertext[0] != VERSION) {
            throw new UndecryptableTokenException("Unsupported encrypted token format", null);
        }
        ByteBuffer buffer = ByteBuffer.wrap(ciphertext, 1, ciphertext.length - 1);
        byte[] iv = new byte[IV_BYTES];
        buffer.get(iv);
        byte[] sealed = new byte[buffer.remaining()];
        buffer.get(sealed);
        try {
            return new String(cipher(Cipher.DECRYPT_MODE, iv, owner).doFinal(sealed), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException ex) {
            throw new UndecryptableTokenException("Failed to decrypt token", ex);
        }
    }

    private Cipher cipher(int mode, byte[] iv, String owner) throws GeneralSecurityException {
        if (key == null) {
            throw new IllegalStateException("Token encryption is not configured");
        }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, key, new GCMParameterSpec(TAG_BITS, iv));
        cipher.updateAAD(owner.getBytes(StandardCharsets.UTF_8));
        return cipher;
    }
}
