package io.testforge.security;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.testforge.auth.AuthProperties;
import io.testforge.auth.LocalJwtSigner;
import io.testforge.auth.TokenType;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** Reuses the module-auth signer; generated adversarial credentials never enter reports. */
final class JwtNegativeCases {
    private JwtNegativeCases() {}

    static Map<String, String> tokens(
            AuthProperties auth, String role, String valid, String requiredAudience) {
        var configured = auth.roles().get(role);
        if (configured == null || configured.type() != TokenType.LOCAL_JWT)
            throw new IllegalArgumentException(
                    "JWT negative cases require a configured local-jwt role");
        var jwt = auth.localJwt();
        var result = new LinkedHashMap<String, String>();
        result.put(
                "jwt-expired",
                new LocalJwtSigner(
                                jwt.secret(),
                                jwt.issuer(),
                                Duration.ofMinutes(1),
                                Clock.offset(Clock.systemUTC(), Duration.ofDays(-1)))
                        .sign(configured.subject(), configured.claims())
                        .value());
        byte[] key = new byte[48];
        new SecureRandom().nextBytes(key);
        result.put(
                "jwt-wrong-signature",
                new LocalJwtSigner(
                                Base64.getEncoder().encodeToString(key),
                                jwt.issuer(),
                                jwt.ttl(),
                                Clock.systemUTC())
                        .sign(configured.subject(), configured.claims())
                        .value());
        String[] parts = valid.split("\\.", -1);
        if (parts.length != 3)
            throw new IllegalArgumentException("local-jwt control is not a compact JWT");
        result.put(
                "jwt-none",
                Base64.getUrlEncoder()
                                .withoutPadding()
                                .encodeToString(
                                        "{\"alg\":\"none\",\"typ\":\"JWT\"}"
                                                .getBytes(java.nio.charset.StandardCharsets.UTF_8))
                        + "."
                        + parts[1]
                        + ".");
        try {
            var mapper = new ObjectMapper();
            var payload =
                    (com.fasterxml.jackson.databind.node.ObjectNode)
                            mapper.readTree(Base64.getUrlDecoder().decode(parts[1]));
            payload.put("roles", "security-other-role");
            result.put(
                    "jwt-tampered-role",
                    parts[0]
                            + "."
                            + Base64.getUrlEncoder()
                                    .withoutPadding()
                                    .encodeToString(mapper.writeValueAsBytes(payload))
                            + "."
                            + parts[2]);
        } catch (Exception e) {
            throw new IllegalStateException("local-jwt control payload cannot be decoded");
        }
        if (requiredAudience != null && !requiredAudience.isBlank()) {
            var claims = new HashMap<>(configured.claims());
            claims.put("aud", requiredAudience + "-different");
            result.put(
                    "jwt-wrong-audience",
                    new LocalJwtSigner(jwt.secret(), jwt.issuer(), jwt.ttl(), Clock.systemUTC())
                            .sign(configured.subject(), claims)
                            .value());
        }
        return result;
    }
}
