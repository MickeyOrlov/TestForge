package io.testforge.example.apiload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.List;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Operation;
import io.testforge.api.explorer.ExplorableOperation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ApiLoadArtifactsTest {
    @TempDir Path dir;

    @Test void selectedMethodsAndPathsNeverBroadenIntoUnselectedWrites() {
        OpenAPI api = new OpenAPI().paths(new Paths()
                .addPathItem("/health", new PathItem().get(new Operation()).post(new Operation()))
                .addPathItem("/tasks", new PathItem().get(new Operation()).post(new Operation())));
        assertThat(new SelectedApiOperations(List.of("GET /health", "POST /tasks")).select("api", api))
                .extracting(ExplorableOperation::key).containsExactly("GET /health", "POST /tasks");
    }

    @Test void preparedBundlesAreBoundToTheirTargetEntrypointAndGeneratedClient() throws Exception {
        Path prepared = dir.resolve("prepared");
        Files.createDirectories(prepared);
        Path script = prepared.resolve("scenario.ts");
        Path client = prepared.resolve("client.ts");
        Files.writeString(script, "import './client.ts';");
        Files.writeString(client, "// generated client");
        new ObjectMapper().writeValue(prepared.resolve("provenance.json").toFile(), Map.of(
                "formatVersion", 1, "entrypoint", "scenario.ts", "targetSha256", ApiLoadArtifacts.targetHash("http://127.0.0.1:8080"),
                "files", Map.of("scenario.ts", ApiLoadArtifacts.sha256(Files.readAllBytes(script)),
                        "client.ts", ApiLoadArtifacts.sha256(Files.readAllBytes(client)))));
        assertThat(ApiLoadArtifacts.verifyPrepared(prepared, script, "http://127.0.0.1:8080"))
                .isEqualTo(prepared.resolve("provenance.json"));
        assertThatThrownBy(() -> ApiLoadArtifacts.verifyPrepared(prepared, script, "http://different.example.test"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("different inputs/target");
        assertThatThrownBy(() -> ApiLoadArtifacts.verifyPrepared(prepared, prepared.resolve("other.ts"), "http://127.0.0.1:8080"))
                .isInstanceOf(IllegalStateException.class);
        Files.writeString(client, "// changed after review");
        assertThatThrownBy(() -> ApiLoadArtifacts.verifyPrepared(prepared, script, "http://127.0.0.1:8080"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("changed after preparation");
    }
}
