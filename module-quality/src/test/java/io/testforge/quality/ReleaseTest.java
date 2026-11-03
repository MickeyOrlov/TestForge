package io.testforge.quality;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The load dimension from module-load's report.json, and `testforge-quality release`. */
class ReleaseTest {

    @TempDir
    Path dir;

    private final ObjectMapper mapper = new ObjectMapper();
    private final PrintStream quiet = new PrintStream(OutputStream.nullOutputStream());

    // the layout module-load writes (a petclinic run, shortened)
    private static String loadReport(String outcome, int thresholds, int failed, String detail) {
        return """
                {"formatVersion": 1, "runId": "c9bf7779", "outcome": "%s", "k6Version": "2.3.0",
                 "scriptSha256": "f76e985538f9f11aba2defe8ff192d46816ddaf3a80a7be64ba06ee7ec4bb37e",
                 "exitCode": 0, "detail": "%s",
                 "metrics": {"iterations": 601, "requests": 2404, "requestsPerSecond": 80.1, "p95Millis": 4.96,
                   "errorRate": 0.0, "droppedIterations": 0, "thresholds": %d, "failedThresholds": %d,
                   "emptyThresholds": 0}}
                """.formatted(outcome, detail, thresholds, failed);
    }

    private Path load(String json) throws IOException {
        Path file = dir.resolve("load/run/report.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, json);
        return file;
    }

    private QualityDimension judge(Path report) {
        return ModuleDimensions.load(null, mapper, List.of(report.toString()), null);
    }

    @Test
    void aLoadRunIsJudgedByItsNativeThresholdsAndAnInvalidRunMeasuresNothing() throws IOException {
        QualityDimension passed = judge(load(loadReport("PASSED", 6, 0, "all native thresholds passed")));
        assertThat(passed.status()).isEqualTo(QualityStatus.PASS);
        assertThat(passed.score()).isEqualTo(100);
        assertThat(passed.facts().getFirst()).isEqualTo("k6 2.3.0 PASSED: 2404 requests at 80.1/s, p95 5.0 ms, "
                + "errors 0.00%, dropped 0, thresholds 6 of 6 passed (load script f76e985538f9)");

        QualityDimension failed = judge(load(loadReport("THRESHOLDS_FAILED", 6, 3, "Native k6 thresholds failed")));
        assertThat(failed.status()).isEqualTo(QualityStatus.FAIL);
        assertThat(failed.score()).isEqualTo(50);
        assertThat(failed.issues()).containsExactly("load script f76e985538f9");

        QualityDimension invalid = judge(load(loadReport("INVALID_RESULT", 6, 0,
                "2 native thresholds saw no sample")));
        assertThat(invalid.status()).as("never a pass").isEqualTo(QualityStatus.NOT_MEASURED);
        assertThat(invalid.facts().getFirst()).isEqualTo("load run c9bf7779 ended INVALID_RESULT: 2 native "
                + "thresholds saw no sample — no valid measurement");

        assertThat(judge(load("{\"formatVersion\": 2, \"outcome\": \"PASSED\"}")).facts().getFirst())
                .contains("unknown layout");
        assertThat(judge(dir.resolve("absent")).facts().getFirst()).startsWith("no module-load report.json at");
        assertThat(ModuleDimensions.load(null, mapper, List.of(), null).status()).isEqualTo(QualityStatus.NOT_MEASURED);
    }

    private Path project(String cases, String loadOutcome) throws IOException {
        Path results = dir.resolve("results/TEST-s.xml");
        Files.createDirectories(results.getParent());
        Files.writeString(results, "<testsuite name=\"s\"><testcase classname=\"io.acme.orders.OrdersTest\" "
                + "name=\"lists\"/>" + cases + "</testsuite>");
        List<String> load = new ArrayList<>();
        if (loadOutcome != null) {
            load.add(load(loadReport(loadOutcome, 6, loadOutcome.equals("PASSED") ? 0 : 2, "x")).toString());
        }
        Path config = dir.resolve("quality.yml");
        Files.writeString(config, """
                forge:
                  quality:
                    junit-results: [%s]
                    artifacts-root: %s
                    output-dir: %s
                    baseline-file: %s
                    components:
                      orders: {owner: team-orders, match: ["io.acme.orders.*"]}
                    load:
                      reports: %s
                    release:
                      required: [functional, load]
                """.formatted(dir.resolve("results"), dir.resolve("index"), dir.resolve("out"),
                dir.resolve("baseline.json"), load));
        return config;
    }

    private int release(Path config, String... settings) {
        List<String> args = new ArrayList<>(List.of("release", "--config", config.toString()));
        for (String setting : settings) {
            args.add("--set");
            args.add(setting);
        }
        return QualityCli.run(args.toArray(String[]::new), quiet, quiet);
    }

    private ReleaseReport read() throws IOException {
        return mapper.copy().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .readValue(dir.resolve("out/release.json").toFile(), ReleaseReport.class);
    }

    @Test
    void readyWhenEverythingRequiredIsMeasuredAndNothingBlockingFails() throws IOException {
        assertThat(release(project("", "PASSED"))).isEqualTo(QualityCli.PASS);
        ReleaseReport report = read();
        assertThat(report.verdict()).isEqualTo(ReleaseReport.Verdict.READY);
        assertThat(report.dimensions()).anySatisfy(d -> {
            assertThat(d.name()).isEqualTo("load");
            assertThat(d.role()).isEqualTo("required");
        });
        assertThat(Files.readString(dir.resolve("out/release.md"))).startsWith("### Release: READY");
    }

    @Test
    void unknownWhileARequiredDimensionIsNotMeasured() throws IOException {
        assertThat(release(project("", null))).isEqualTo(QualityCli.CANNOT_JUDGE);
        ReleaseReport report = read();
        assertThat(report.verdict()).isEqualTo(ReleaseReport.Verdict.UNKNOWN);
        assertThat(report.unmeasured()).singleElement().asString().startsWith("load (no load run");
        assertThat(Files.readString(dir.resolve("out/release.md"))).startsWith("### Release: UNKNOWN — not measured: load");
    }

    @Test
    void aFailureTheBaselineAlreadyKnewStillBlocksARelease() throws IOException {
        Path config = project("<testcase classname=\"io.acme.orders.OrdersTest\" name=\"pays\">"
                + "<failure message=\"x\"/></testcase>", "PASSED");
        assertThat(QualityCli.run(new String[] {"baseline", "--config", config.toString()}, quiet, quiet))
                .isEqualTo(QualityCli.PASS);
        assertThat(QualityCli.run(new String[] {"check", "--config", config.toString()}, quiet, quiet))
                .as("the everyday gate: nothing new").isEqualTo(QualityCli.PASS);

        assertThat(release(config)).as("the release: still failing").isEqualTo(QualityCli.FAILED);
        assertThat(read().blockers()).containsExactly("functional FAIL (1 open: io.acme.orders.OrdersTest.pays)");

        assertThat(release(project("", "THRESHOLDS_FAILED"))).isEqualTo(QualityCli.FAILED);
        assertThat(read().blockers()).singleElement().asString().startsWith("load FAIL (1 open: load script");
    }

    @Test
    void acceptedIssuesShipAsRisksWithOwnerReasonAndExpiryAndAnExpiredOneBlocks() throws IOException {
        Path config = project("<testcase classname=\"io.acme.orders.OrdersTest\" name=\"pays\">"
                + "<failure message=\"x\"/></testcase>", "PASSED");

        assertThat(release(config, "accepted[0]=functional io.acme.orders.OrdersTest.pays  # ORD-12 sandbox, "
                + "until 2099-12-31")).isEqualTo(QualityCli.PASS);
        ReleaseReport accepted = read();
        assertThat(accepted.verdict()).isEqualTo(ReleaseReport.Verdict.READY);
        assertThat(accepted.risks()).containsExactly(new ReleaseReport.Risk("functional",
                "io.acme.orders.OrdersTest.pays", "team-orders", "ORD-12 sandbox, until 2099-12-31", "2099-12-31",
                false));
        assertThat(Files.readString(dir.resolve("out/release.md")))
                .contains("**Accepted risks shipped with this release**")
                .contains("| functional | `io.acme.orders.OrdersTest.pays` | team-orders | ORD-12 sandbox");

        assertThat(release(config, "accepted[0]=functional io.acme.orders.OrdersTest.pays  # ORD-12, until 2020-01-01"))
                .isEqualTo(QualityCli.FAILED);
        assertThat(read().risks()).singleElement().satisfies(risk -> assertThat(risk.expired()).isTrue());
    }

    @Test
    void releaseLimitsAreAbsoluteAndTheJsonFollowsItsSchema() throws IOException {
        Path config = project("", "PASSED");
        assertThat(release(config, "release.limits.load.min-score=101")).isEqualTo(QualityCli.FAILED);
        assertThat(read().blockers()).containsExactly("load (score 100 below the release minimum 101)");

        JsonNode schema = mapper.readTree(dir.resolve("out/release.schema.json").toFile());
        JsonNode json = mapper.readTree(dir.resolve("out/release.json").toFile());
        List<String> fields = new ArrayList<>();
        json.fieldNames().forEachRemaining(fields::add);
        List<String> declared = new ArrayList<>();
        schema.path("properties").fieldNames().forEachRemaining(declared::add);
        assertThat(fields).as("a field release.json gains belongs in the schema").isSubsetOf(declared);
        schema.path("required").forEach(field -> assertThat(json.has(field.asText())).as(field.asText()).isTrue());
    }
}
