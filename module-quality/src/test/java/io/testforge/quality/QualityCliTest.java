package io.testforge.quality;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The gate as a command: same configuration, a verdict in quality.json, an exit code to act on. */
class QualityCliTest {

    @TempDir
    Path dir;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    private int cli(String... args) {
        out.reset();
        err.reset();
        return QualityCli.run(args, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    private String out() {
        return out.toString(StandardCharsets.UTF_8);
    }

    private String err() {
        return err.toString(StandardCharsets.UTF_8);
    }

    private Path config(String failing) throws IOException {
        Path results = dir.resolve("results/TEST-s.xml");
        Files.createDirectories(results.getParent());
        Files.writeString(results, "<testsuite name=\"s\"><testcase classname=\"io.acme.T\" name=\"ok\"/>"
                + failing + "</testsuite>");
        Path config = dir.resolve("quality.yml");
        Files.writeString(config, """
                forge:
                  quality:
                    junit-results: [%s]
                    artifacts-root: %s
                    output-dir: %s
                    baseline-file: %s
                """.formatted(dir.resolve("results"), dir.resolve("artifacts"), dir.resolve("out"),
                dir.resolve("baseline.json")));
        return config;
    }

    @Test
    void checkExitsWithTheGatesVerdictAndWritesItDown() throws IOException {
        Path config = config("<testcase classname=\"io.acme.T\" name=\"pays\"><failure message=\"x\"/></testcase>");

        assertThat(cli("check", "--config", config.toString())).isEqualTo(QualityCli.FAILED);
        assertThat(out()).startsWith("### Quality gate: FAIL · overall FAIL");
        assertThat(err()).contains("quality gate: FAIL");
        JsonNode json = new ObjectMapper().readTree(dir.resolve("out/quality.json").toFile());
        assertThat(json.path("gate").path("verdict").asText()).isEqualTo("FAIL");
        assertThat(json.path("gate").path("failures").get(0).asText()).startsWith("functional (1 new: io.acme.T.pays — 2 tests: 1 passed");

        assertThat(cli("baseline", "--config", config.toString())).isEqualTo(QualityCli.PASS);
        assertThat(dir.resolve("baseline.json")).exists();
        assertThat(cli("check", "--config", config.toString(), "--json")).as("the failure is known now")
                .isEqualTo(QualityCli.PASS);
        assertThat(new ObjectMapper().readTree(out()).path("gate").path("verdict").asText()).isEqualTo("PASS");
        assertThat(Files.readString(dir.resolve("out/quality.md")))
                .contains("- gate: **PASS** — what fails is known or accepted, nothing new");
    }

    @Test
    void setOverridesTheFileAndNothingMeasuredCannotBeJudged() throws IOException {
        Path config = config("");
        assertThat(cli("check", "--config", config.toString(), "--set", "min-tests=5")).isEqualTo(QualityCli.FAILED);
        assertThat(cli("--config", config.toString())).as("check is the default command").isEqualTo(QualityCli.PASS);

        assertThat(cli("check", "--config", config.toString(), "--set",
                "forge.quality.junit-results=" + dir.resolve("none"))).isEqualTo(QualityCli.CANNOT_JUDGE);
        assertThat(err()).contains("nothing could be measured");
        assertThat(cli("check", "--config", dir.resolve("missing.yml").toString())).isEqualTo(QualityCli.CANNOT_JUDGE);
        assertThat(err()).contains("cannot judge — No configuration at");
    }

    @Test
    void aBaselineFromARunThatMeasuredNothingLeavesTheAcceptedOneAlone() throws IOException {
        Path config = config("");
        assertThat(cli("baseline", "--config", config.toString())).isEqualTo(QualityCli.PASS);
        byte[] accepted = Files.readAllBytes(dir.resolve("baseline.json"));

        assertThat(cli("baseline", "--config", config.toString(), "--set",
                "junit-results=" + dir.resolve("none"))).isEqualTo(QualityCli.CANNOT_JUDGE);
        assertThat(err()).contains("baseline").contains("is unchanged");
        assertThat(Files.readAllBytes(dir.resolve("baseline.json"))).isEqualTo(accepted);

        assertThat(cli("baseline", "--config", config.toString(), "--set", "required[0]=contracts"))
                .as("a required dimension not measured").isEqualTo(QualityCli.CANNOT_JUDGE);
        assertThat(err()).contains("Required dimensions not measured (contracts)");
        assertThat(Files.readAllBytes(dir.resolve("baseline.json"))).isEqualTo(accepted);
    }

    @Test
    void aWrongCommandLineSaysHowToUseIt() {
        assertThat(cli("frobnicate")).isEqualTo(QualityCli.USAGE);
        assertThat(cli("check", "--set", "novalue")).isEqualTo(QualityCli.USAGE);
        assertThat(cli("check", "--config")).isEqualTo(QualityCli.USAGE);
        assertThat(err()).contains("usage: testforge-quality check|baseline");
    }
}
