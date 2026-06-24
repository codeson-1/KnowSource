package com.knowsource.security;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class JwtService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;
    private final byte[] secret;
    private final long accessTokenTtlSeconds;

    public JwtService(
            ObjectMapper objectMapper,
            @Value("${knowsource.security.jwt.secret:dev-only-change-me-dev-only-change-me}") String secret,
            @Value("${knowsource.security.jwt.access-token-ttl-seconds:900}") long accessTokenTtlSeconds) {
        this.objectMapper = objectMapper;
        this.secret = requireSecret(secret).getBytes(StandardCharsets.UTF_8);
        this.accessTokenTtlSeconds = Math.max(60, accessTokenTtlSeconds);
    }

    public String createAccessToken(CurrentUser user) {
        long now = Instant.now().getEpochSecond();
        return sign(Map.of("alg", "HS256", "typ", "JWT"), Map.of(
                "sub", user.username(),
                "uid", user.id(),
                "role", user.globalRole(),
                "iat", now,
                "exp", now + accessTokenTtlSeconds));
    }

    public CurrentUser parseAccessToken(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3) {
                throw new IllegalArgumentException("Invalid JWT.");
            }
            String expectedSignature = hmac(parts[0] + "." + parts[1]);
            if (!constantTimeEquals(expectedSignature, parts[2])) {
                throw new IllegalArgumentException("Invalid JWT signature.");
            }
            Map<String, Object> payload = objectMapper.readValue(base64UrlDecode(parts[1]), MAP_TYPE);
            long exp = ((Number) payload.get("exp")).longValue();
            if (exp < Instant.now().getEpochSecond()) {
                throw new IllegalArgumentException("JWT expired.");
            }
            return new CurrentUser(
                    ((Number) payload.get("uid")).longValue(),
                    payload.get("sub").toString(),
                    payload.get("role").toString());
        } catch (Exception ex) {
            throw new IllegalArgumentException("Invalid access token.", ex);
        }
    }

    private String sign(Map<String, Object> header, Map<String, Object> payload) {
        try {
            String encodedHeader = base64UrlEncode(objectMapper.writeValueAsBytes(header));
            String encodedPayload = base64UrlEncode(objectMapper.writeValueAsBytes(payload));
            String signingInput = encodedHeader + "." + encodedPayload;
            return signingInput + "." + hmac(signingInput);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to create JWT.", ex);
        }
    }

    private String hmac(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return base64UrlEncode(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to sign JWT.", ex);
        }
    }

    private static String base64UrlEncode(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static byte[] base64UrlDecode(String value) {
        return Base64.getUrlDecoder().decode(value);
    }

    private static boolean constantTimeEquals(String left, String right) {
        return java.security.MessageDigest.isEqual(
                left.getBytes(StandardCharsets.UTF_8),
                right.getBytes(StandardCharsets.UTF_8));
    }

    private static String requireSecret(String secret) {
        if (!StringUtils.hasText(secret) || secret.trim().length() < 32) {
            throw new IllegalArgumentException("JWT secret must contain at least 32 characters.");
        }
        return secret.trim();
    }
}
