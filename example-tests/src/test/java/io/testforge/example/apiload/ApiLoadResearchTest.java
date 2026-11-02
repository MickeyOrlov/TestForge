package io.testforge.example.apiload;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.testforge.api.discovery.ApiDiscoveryRunner;
import io.testforge.api.discovery.TestForgeApiDiscoveryAutoConfiguration;
import io.testforge.api.explorer.ApiExplorerRunner;
import io.testforge.api.explorer.OperationSelector;
import io.testforge.api.explorer.TestForgeApiExplorerAutoConfiguration;
import io.testforge.http.ApiRequestCustomizer;
import io.testforge.http.TestForgeHttpAutoConfiguration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Project-level research stage; never invokes k6 and never runs in the default test task. */
@Tag("api-load-research")
class ApiLoadResearchTest {
    @Test void studiesTheSelectedApiSnapshotAndAttestsTheFiles() throws Exception {
        Path run = Path.of(System.getProperty("testforge.api-load.run-dir"));
        String target = System.getProperty("testforge.api-load.target");
        String started = Instant.now().toString();
        var inputs = ApiLoadArtifacts.verifyInputs(run);
        ObjectMapper mapper = new ObjectMapper();
        List<String> selected = Arrays.asList(mapper.readValue(run.resolve("input/operations.json").toFile(), String[].class));
        String methods = String.join(",", selected.stream().map(key -> key.split(" ", 2)[0]).distinct().toList());
        String paths = String.join(",", selected.stream().map(key -> key.split(" ", 2)[1]).distinct().toList());
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(TestForgeApiDiscoveryAutoConfiguration.class,
                        TestForgeHttpAutoConfiguration.class, TestForgeApiExplorerAutoConfiguration.class))
                .withBean(OperationSelector.class, () -> new SelectedApiOperations(selected))
                .withBean(ApiRequestCustomizer.class, () -> (request, service) -> {
                    String token = System.getenv("LOAD_API_TOKEN");
                    if (token != null && !token.isBlank()) request.header("Authorization", "Bearer " + token);
                })
                .withPropertyValues("forge.api-discovery.enabled=true",
                        "forge.api-discovery.output-dir=" + run.resolve("discovery"),
                        "forge.api-discovery.baseline-dir=" + run.resolve("unused-baseline"),
                        "forge.api-discovery.specs.api.location=" + run.resolve("input/openapi.json"),
                        "forge.api-explorer.enabled=true", "forge.api-explorer.output-dir=" + run.resolve("exploration"),
                        "forge.api-explorer.specs=api", "forge.api-explorer.methods=" + methods,
                        "forge.api-explorer.include-paths=" + paths,
                        "forge.api-explorer.allow-unsafe-methods=" + inputs.path("allowWrites").asBoolean(),
                        "forge.api-explorer.fail-on.contract-mismatch=true", "forge.http.base-url=" + target,
                        "forge.http.logging.enabled=false", "forge.http.summary.enabled=false", "forge.http.retry.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    context.getBean(ApiDiscoveryRunner.class).assertHealthy();
                    context.getBean(ApiExplorerRunner.class).assertHealthy();
                });
        ApiLoadArtifacts.verifyInputs(run);
        Map<String, String> hashes = new LinkedHashMap<>();
        for (String file : List.of("discovery/report.json", "discovery/api/catalog.json", "exploration/report.json")) {
            hashes.put(file, ApiLoadArtifacts.sha256(Files.readAllBytes(run.resolve(file))));
        }
        mapper.writerWithDefaultPrettyPrinter().writeValue(run.resolve("research.json").toFile(), Map.of(
                "formatVersion", 1, "startedAt", started, "finishedAt", Instant.now().toString(),
                "specSha256", inputs.path("files").path("openapi.json").asText(),
                "targetSha256", ApiLoadArtifacts.targetHash(target), "files", hashes));
    }
}
