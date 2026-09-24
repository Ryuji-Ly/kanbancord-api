package com.kanbancord_api.security;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@Service
public class JwtTokenService {

    private static final Logger log = LoggerFactory.getLogger(JwtTokenService.class);
    private static final int MIN_SECRET_BYTES = 32;
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder URL_DECODER = Base64.getUrlDecoder();

    private final AuthJwtProperties authJwtProperties;
    private final ObjectMapper objectMapper;

    public JwtTokenService(AuthJwtProperties authJwtProperties, ObjectMapper objectMapper) {
        this.authJwtProperties = authJwtProperties;
        this.objectMapper = objectMapper;
        validateSecret(authJwtProperties.getSecret());
    }

    /**
     * HS256 secrets shorter than 256 bits can be brute-forced offline from any issued token, so refuse
     * to start with one. An empty secret is allowed at startup (login simply fails) so tooling and
     * tests can boot without auth configured.
     */
    private static void validateSecret(String secret) {
        if (secret == null || secret.isBlank()) {
            log.warn("kanbancord.auth.jwt.secret is not set; user login is disabled");
            return;
        }
        if (secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "kanbancord.auth.jwt.secret must be at least " + MIN_SECRET_BYTES + " bytes");
        }
    }

    public String issueToken(Long userId) {
        Instant now = Instant.now();
        Instant exp = now.plusSeconds(authJwtProperties.getExpirationSeconds());

        Map<String, Object> header = Map.of("alg", "HS256", "typ", "JWT");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sub", String.valueOf(userId));
        payload.put("uid", userId);
        payload.put("iat", now.getEpochSecond());
        payload.put("exp", exp.getEpochSecond());

        String headerPart = encodeJson(header);
        String payloadPart = encodeJson(payload);
        String signingInput = headerPart + "." + payloadPart;
        String signature = sign(signingInput);
        return signingInput + "." + signature;
    }

    public Optional<Long> validateAndExtractUserId(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3) {
                return Optional.empty();
            }

            String signingInput = parts[0] + "." + parts[1];
            String expectedSignature = sign(signingInput);
            if (!constantTimeEquals(expectedSignature, parts[2])) {
                return Optional.empty();
            }

            Map<String, Object> payload = objectMapper.readValue(
                    URL_DECODER.decode(parts[1]),
                    new TypeReference<>() {
                    });

            long exp = toLong(payload.get("exp"));
            if (Instant.now().getEpochSecond() >= exp) {
                return Optional.empty();
            }

            return Optional.of(toLong(payload.get("uid")));
        } catch (Exception ex) {
            return Optional.empty();
        }
    }

    public long getExpirationSeconds() {
        return authJwtProperties.getExpirationSeconds();
    }

    private String encodeJson(Map<String, Object> map) {
        try {
            byte[] bytes = objectMapper.writeValueAsBytes(map);
            return URL_ENCODER.encodeToString(bytes);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to encode JWT payload", ex);
        }
    }

    private String sign(String input) {
        String secret = authJwtProperties.getSecret();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("kanbancord.auth.jwt.secret must be configured");
        }

        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] signature = mac.doFinal(input.getBytes(StandardCharsets.UTF_8));
            return URL_ENCODER.encodeToString(signature);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to sign JWT", ex);
        }
    }

    private long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }

    private boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }
}
