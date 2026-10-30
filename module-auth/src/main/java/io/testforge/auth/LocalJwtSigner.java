package io.testforge.auth;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Signs HS256 JWTs for systems under test that accept a shared test secret,
 * so suites need no identity provider at all.
 *
 * <p>Deliberately minimal: HS256 only, flat string claims. It exists to stop
 * every project hand-rolling the same few lines, not to be a JOSE library.
 */
public class LocalJwtSigner {

    static final int MIN_SECRET_BYTES = 32;

    private static final Base64.Encoder BASE64URL = Base64.getUrlEncoder().withoutPadding();

    private final byte[] secret;
    private final String issuer;
    private final Duration ttl;
    private final Clock clock;
    private final ObjectMapper mapper = new ObjectMapper();

    public LocalJwtSigner(String secret, String issuer, Duration ttl, Clock clock) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalArgumentException(
                    "forge.auth.local-jwt.secret must be at least %d bytes for HS256".formatted(MIN_SECRET_BYTES));
        }
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.issuer = issuer;
        this.ttl = ttl;
        this.clock = clock;
    }

    public AccessToken sign(String subject, Map<String, String> claims) {
        Instant issuedAt = clock.instant();
        Instant expiresAt = issuedAt.plus(ttl);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("iss", issuer);
        if (subject != null && !subject.isBlank()) {
            payload.put("sub", subject);
        }
        payload.put("iat", issuedAt.getEpochSecond());
        payload.put("exp", expiresAt.getEpochSecond());
        payload.put("jti", UUID.randomUUID().toString());
        // reserved claims stay under the signer's control
        claims.forEach((name, value) -> {
            if (!payload.containsKey(name)) {
                payload.put(name, value);
            }
        });

        String unsigned = encode(Map.of("alg", "HS256", "typ", "JWT")) + "." + encode(payload);
        return new AccessToken(unsigned + "." + BASE64URL.encodeToString(hmac(unsigned)), expiresAt);
    }

    private String encode(Map<String, ?> json) {
        try {
            return BASE64URL.encodeToString(mapper.writeValueAsBytes(json));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize JWT part", e);
        }
    }

    private byte[] hmac(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.US_ASCII));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is not available", e);
        }
    }
}
