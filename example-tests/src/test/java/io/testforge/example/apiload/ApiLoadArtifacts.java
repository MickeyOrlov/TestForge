package io.testforge.example.apiload;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Project composition over files. No production module needs to know this workflow exists. */
public final class ApiLoadArtifacts {
    private ApiLoadArtifacts() {
    }

    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JDK", e);
        }
    }

    public static String targetHash(String baseUrl) {
        return sha256(baseUrl.getBytes(StandardCharsets.UTF_8));
    }

    public static JsonNode verifyInputs(Path run) throws IOException {
        JsonNode inputs = new ObjectMapper().readTree(run.resolve("inputs.json").toFile());
        if (inputs == null || inputs.path("formatVersion").asInt() != 1) {
            throw new IllegalStateException("Unsupported API load input snapshot");
        }
        verifyFiles(run.resolve("input"), inputs.path("files"));
        return inputs;
    }

    public static Path verifyPrepared(Path prepared, Path script, String baseUrl) throws IOException {
        JsonNode provenance = new ObjectMapper().readTree(prepared.resolve("provenance.json").toFile());
        if (provenance == null || provenance.path("formatVersion").asInt() != 1
                || !targetHash(baseUrl).equals(provenance.path("targetSha256").asText())
                || !"scenario.ts".equals(provenance.path("entrypoint").asText())
                || !prepared.resolve("scenario.ts").toAbsolutePath().normalize().equals(script.toAbsolutePath().normalize())) {
            throw new IllegalStateException("Prepared scenario belongs to different inputs/target; rerun the API load workflow");
        }
        verifyFiles(prepared, provenance.path("files"));
        return prepared.resolve("provenance.json");
    }

    private static void verifyFiles(Path root, JsonNode hashes) throws IOException {
        if (!hashes.isObject() || hashes.isEmpty()) {
            throw new IllegalStateException("Missing API load artifact checksums");
        }
        Path base = root.toRealPath();
        for (var file : hashes.properties()) {
            Path path = base.resolve(file.getKey()).normalize();
            if (!path.startsWith(base) || !Files.isRegularFile(path) || !path.toRealPath().startsWith(base)
                    || !sha256(Files.readAllBytes(path)).equals(file.getValue().asText())) {
                throw new IllegalStateException("API load artifact changed after preparation; rerun the workflow");
            }
        }
    }
}
