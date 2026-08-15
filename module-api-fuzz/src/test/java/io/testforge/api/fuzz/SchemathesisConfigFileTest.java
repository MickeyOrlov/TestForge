package io.testforge.api.fuzz;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SchemathesisConfigFileTest {

    @Test
    void defaultPolicyGeneratesConfigFileWithUnexpectedMethodsSuppressed(@TempDir Path tempDir) throws IOException {
        ApiFuzzProperties props = new ApiFuzzProperties(
                null, null, null, null, null, false, null, null, null, null, null, null, null, null,
                null);
        FuzzSafetyPolicy policy = FuzzSafetyPolicy.from(props);

        SchemathesisConfigFile writer = new SchemathesisConfigFile();
        Path configFile = writer.write(tempDir, policy);

        assertThat(configFile).isNotNull();
        assertThat(configFile).exists();
        assertThat(configFile.getFileName().toString()).isEqualTo("schemathesis.toml");

        String content = Files.readString(configFile);
        assertThat(content).contains("[phases.coverage]");
        assertThat(content).contains("unexpected-methods = []");
    }

    @Test
    void allowUnsafeMethodsTrueDoesNotSuppressUnexpectedMethods(@TempDir Path tempDir) throws IOException {
        ApiFuzzProperties props = new ApiFuzzProperties(
                null, null, null, null, Set.of("GET", "POST"), true, null, null, null, null, null, null, null, null,
                null);
        FuzzSafetyPolicy policy = FuzzSafetyPolicy.from(props);

        Path configFile = SchemathesisConfigFile.generate(tempDir, policy);

        assertThat(configFile).exists();
        String content = Files.readString(configFile);
        assertThat(content).doesNotContain("unexpected-methods = []");
    }
}
