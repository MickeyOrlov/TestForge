package io.testforge.api.fuzz;

import io.swagger.v3.core.util.Json;
import io.swagger.v3.oas.models.OpenAPI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * A stable hash of the document a run was made against.
 *
 * <p>Its only job is to answer one question later: is the replay running
 * against the same document that produced the finding? Without it, a case id
 * reproduced six months on can quietly be testing a constraint that no longer
 * exists, and the run will report a green result nobody should trust.
 *
 * <p>Computed over the parsed and fully resolved model rather than the source
 * text, so reformatting the document does not change it and inlining a
 * {@code $ref} does not either. What changes it is what should: a constraint,
 * an operation, a schema.
 */
final class SpecFingerprint {

    private SpecFingerprint() {
    }

    static String of(OpenAPI openApi) {
        try {
            String canonical = Json.pretty().writeValueAsString(openApi);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            // 16 hex characters is plenty to notice a changed document and short
            // enough to sit in a report line
            return "sha256:" + HexFormat.of().formatHex(hash).substring(0, 16);
        } catch (NoSuchAlgorithmException | RuntimeException e) {
            return "unavailable";
        } catch (Exception e) {
            return "unavailable";
        }
    }
}
