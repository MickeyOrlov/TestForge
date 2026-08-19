package io.testforge.api.fuzz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SchemathesisCommandTest {

    @Test
    void defaultPropertiesProduceIncludeMethodGetHeadOptionsAndNoMore(@TempDir Path tempDir) {
        ApiFuzzProperties props = new ApiFuzzProperties(
                true, "build/api-fuzz", List.of(), "http://localhost:8080",
                null, false, null, 12345L, null, null, null, null, "st", null,
                null);
        FuzzSafetyPolicy policy = FuzzSafetyPolicy.from(props);
        Path specPath = tempDir.resolve("openapi.yaml");
        Path generatedConfig = tempDir.resolve("schemathesis.toml");

        List<String> command = SchemathesisCommand.build(props, policy, specPath, generatedConfig);

        assertThat(command).containsSubsequence(
                "--include-method", "GET",
                "--include-method", "HEAD",
                "--include-method", "OPTIONS"
        );

        long includeMethodCount = command.stream().filter("--include-method"::equals).count();
        assertThat(includeMethodCount).isEqualTo(3);
    }

    @Test
    void listingPostWithoutAllowUnsafeMethodsDoesNotProduceIncludeMethodPost(@TempDir Path tempDir) {
        ApiFuzzProperties props = new ApiFuzzProperties(
                true, "build/api-fuzz", List.of(), "http://localhost:8080",
                Set.of("GET", "POST"), false, null, null, null, null, null, null, null, null,
                null);
        FuzzSafetyPolicy policy = FuzzSafetyPolicy.from(props);
        Path specPath = tempDir.resolve("openapi.yaml");
        Path generatedConfig = tempDir.resolve("schemathesis.toml");

        List<String> command = SchemathesisCommand.build(props, policy, specPath, generatedConfig);

        assertThat(command).contains("--include-method", "GET");
        assertThat(command).doesNotContain("POST");
    }

    @Test
    void listingPostWithAllowUnsafeMethodsProducesIncludeMethodPost(@TempDir Path tempDir) {
        ApiFuzzProperties props = new ApiFuzzProperties(
                true, "build/api-fuzz", List.of(), "http://localhost:8080",
                Set.of("GET", "POST"), true, null, null, null, null, null, null, null, null,
                null);
        FuzzSafetyPolicy policy = FuzzSafetyPolicy.from(props);
        Path specPath = tempDir.resolve("openapi.yaml");
        Path generatedConfig = tempDir.resolve("schemathesis.toml");

        List<String> command = SchemathesisCommand.build(props, policy, specPath, generatedConfig);

        assertThat(command).containsSubsequence("--include-method", "GET", "--include-method", "POST");
    }

    @Test
    void configFileAppearsBeforeRunSubcommand(@TempDir Path tempDir) {
        ApiFuzzProperties props = new ApiFuzzProperties(
                true, "build/api-fuzz", List.of(), "http://localhost:8080",
                null, false, null, null, null, null, null, null, "st", "user-schemathesis.toml",
                null);
        FuzzSafetyPolicy policy = FuzzSafetyPolicy.from(props);
        Path specPath = tempDir.resolve("openapi.yaml");
        Path generatedConfig = tempDir.resolve("schemathesis.toml");

        List<String> command = SchemathesisCommand.build(props, policy, specPath, generatedConfig);

        int firstConfigFileIndex = command.indexOf("--config-file");
        int runIndex = command.indexOf("run");

        assertThat(firstConfigFileIndex).isGreaterThanOrEqualTo(0);
        assertThat(runIndex).isGreaterThan(firstConfigFileIndex);
        assertThat(command).containsSubsequence(
                "--config-file", generatedConfig.toString(),
                "--config-file", "user-schemathesis.toml",
                "run"
        );
    }

    /**
     * The child process runs with its working directory set to the per-spec
     * output directory, so a relative {@code --config-file} is resolved against
     * the wrong base and Schemathesis aborts before testing anything. The
     * documented default {@code output-dir: build/api-fuzz} is relative, so that
     * was the default configuration.
     */
    @Test
    void aRelativeGeneratedConfigPathIsPassedAbsolute() {
        ApiFuzzProperties props = new ApiFuzzProperties(
                true, "build/api-fuzz", List.of(), "http://localhost:8080",
                null, false, null, null, null, null, null, null, "st", null,
                null);
        FuzzSafetyPolicy policy = FuzzSafetyPolicy.from(props);
        Path relativeConfig = Path.of("build/api-fuzz/demo/schemathesis.toml");

        List<String> command = SchemathesisCommand.build(
                props, policy, Path.of("build/api-fuzz/demo/openapi.yaml"), relativeConfig);

        String configArgument = command.get(command.indexOf("--config-file") + 1);

        assertThat(Path.of(configArgument))
                .as("a relative path would be resolved against the child's working directory")
                .isAbsolute();
        assertThat(configArgument)
                .isEqualTo(relativeConfig.toAbsolutePath().normalize().toString());
    }

    /** An already-absolute path must survive unchanged. */
    @Test
    void anAbsoluteGeneratedConfigPathIsLeftAlone(@TempDir Path tempDir) {
        ApiFuzzProperties props = new ApiFuzzProperties(
                true, tempDir.toString(), List.of(), "http://localhost:8080",
                null, false, null, null, null, null, null, null, "st", null,
                null);
        FuzzSafetyPolicy policy = FuzzSafetyPolicy.from(props);
        Path absoluteConfig = tempDir.resolve("schemathesis.toml");

        List<String> command = SchemathesisCommand.build(
                props, policy, tempDir.resolve("openapi.yaml"), absoluteConfig);

        assertThat(command).containsSubsequence("--config-file", absoluteConfig.toString());
    }

    @Test
    void seedAndReportFlagsArePresent(@TempDir Path tempDir) {
        ApiFuzzProperties props = new ApiFuzzProperties(
                true, "custom-output", List.of(), "http://localhost:8080",
                null, false, null, 987654321L, 100, "all", 5, 300, "st", null,
                null);
        FuzzSafetyPolicy policy = FuzzSafetyPolicy.from(props);
        Path specPath = tempDir.resolve("openapi.yaml");
        Path generatedConfig = tempDir.resolve("schemathesis.toml");

        List<String> command = SchemathesisCommand.build(props, policy, specPath, generatedConfig);

        assertThat(command).containsSubsequence("--seed", "987654321");
        // Reports land beside this run's generated config -- i.e. the per-spec
        // directory -- not in the shared forge.api-fuzz.output-dir, which with
        // several specs would have them overwrite each other.
        assertThat(command).containsSubsequence(
                "--report", "junit,ndjson", "--report-dir", tempDir.toAbsolutePath().toString());
        assertThat(command).doesNotContain("custom-output");
        assertThat(command).containsSubsequence("--max-failures", "5");
        assertThat(command).contains("--output-sanitize", "true");
        assertThat(command).contains("--no-color");
    }

    @Test
    void argumentListContainsNoForbiddenAuthOrHeaderFlags(@TempDir Path tempDir) {
        ApiFuzzProperties props = new ApiFuzzProperties(
                true, "build/api-fuzz", List.of(), "http://localhost:8080",
                null, false, null, null, null, null, null, null, null, null,
                null);
        FuzzSafetyPolicy policy = FuzzSafetyPolicy.from(props);
        Path specPath = tempDir.resolve("openapi.yaml");
        Path generatedConfig = tempDir.resolve("schemathesis.toml");

        List<String> command = SchemathesisCommand.build(props, policy, specPath, generatedConfig);

        assertThat(command).doesNotContain("-H", "--header", "--auth", "-a");
    }

    @Test
    void missingBaseUrlThrowsApiFuzzException(@TempDir Path tempDir) {
        ApiFuzzProperties props = new ApiFuzzProperties(
                true, "build/api-fuzz", List.of(), null,
                null, false, null, null, null, null, null, null, null, null,
                null);
        FuzzSafetyPolicy policy = FuzzSafetyPolicy.from(props);
        Path specPath = tempDir.resolve("openapi.yaml");
        Path generatedConfig = tempDir.resolve("schemathesis.toml");

        assertThatThrownBy(() -> SchemathesisCommand.build(props, policy, specPath, generatedConfig))
                .isInstanceOf(ApiFuzzException.class)
                .hasMessageContaining("Base URL must be configured");
    }
}
