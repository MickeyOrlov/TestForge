package io.testforge.quality;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.testforge.artifact.ArtifactSink;
import io.testforge.quality.QualityProperties.ComponentProperties;
import io.testforge.quality.QualityProperties.GateProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Run labels, components, expiring acceptance, per-dimension gates and the exports. */
class QualityExportTest {

    @TempDir
    Path dir;

    private final ObjectMapper mapper = new ObjectMapper();
    private final Clock today = Clock.fixed(Instant.parse("2026-11-01T10:00:00Z"), ZoneOffset.UTC);

    private QualityRunner runner(List<String> accepted, Map<String, ComponentProperties> components,
                                 Map<String, GateProperties> gate) {
        return new QualityRunner(new QualityProperties(true, List.of(dir.resolve("results").toString()),
                dir.resolve("artifacts").toString(), null, dir.resolve("quality").toString(), 0.5, null,
                dir.resolve("baseline.json").toString(), accepted, null, null, null, null, null,
                Map.of("environment", "staging", "team name", "qa \"core\""), components, gate, null),
                mapper, ArtifactSink.NO_OP, today);
    }

    private void junit() throws IOException {
        Path path = dir.resolve("results/TEST-s.xml");
        Files.createDirectories(path.getParent());
        Files.writeString(path, "<testsuite name=\"s\">"
                + "<testcase classname=\"io.acme.orders.OrdersTest\" name=\"pays\"><failure message=\"x\"/></testcase>"
                + "<testcase classname=\"io.acme.orders.OrdersTest\" name=\"refunds\"><failure message=\"y\"/></testcase>"
                + "<testcase classname=\"io.acme.billing.BillsTest\" name=\"bills\"><failure message=\"z\"/></testcase>"
                + "<testcase classname=\"io.acme.misc.T\" name=\"ok\"/>"
                + "<testcase classname=\"io.acme.misc.T\" name=\"retried\"><failure message=\"r\"/></testcase>"
                + "<testcase classname=\"io.acme.misc.T\" name=\"retried\"/>"
                + "</testsuite>");
    }

    @Test
    void issuesAreGroupedByTheFirstComponentWhosePatternMatches() throws IOException {
        junit();
        Map<String, ComponentProperties> components = new LinkedHashMap<>();
        components.put("orders", new ComponentProperties("team-orders", List.of("io.acme.orders.*")));
        components.put("everything-acme", new ComponentProperties(null, List.of("io.acme.*Test.*")));

        QualityReport report = runner(null, components, Map.of()).run();

        assertThat(report.components()).extracting(QualityReport.Component::name)
                .containsExactly("orders", "everything-acme", QualityComponents.UNASSIGNED);
        assertThat(report.components().getFirst()).satisfies(orders -> {
            assertThat(orders.owner()).isEqualTo("team-orders");
            assertThat(orders.status()).isEqualTo(QualityStatus.FAIL);
            assertThat(orders.issues()).containsExactly("functional io.acme.orders.OrdersTest.pays",
                    "functional io.acme.orders.OrdersTest.refunds");
            assertThat(orders.newIssues()).isEqualTo(2);
        });
        assertThat(report.components().get(1).issues()).containsExactly("functional io.acme.billing.BillsTest.bills");
        assertThat(report.components().getLast().issues()).as("io.acme.misc.T matches no *Test pattern")
                .containsExactly("stability io.acme.misc.T.retried");
        assertThat(Files.readString(Path.of(report.reportMarkdown())))
                .contains("| orders | team-orders | FAIL | 2 | 2 |");
    }

    @Test
    void anAcceptedEntryExpiresAndOneNoLongerSeenIsNamed() throws IOException {
        junit();
        List<String> accepted = List.of(
                "functional io.acme.orders.OrdersTest.pays  # until 2026-10-31, ORD-12",
                "functional io.acme.orders.OrdersTest.refunds  # until 2026-12-01, ORD-13",
                "functional io.acme.gone.T.x  # fixed long ago");

        QualityReport report = runner(accepted, Map.of(), Map.of()).run();

        QualityDelta delta = report.dimension(QualityDimension.FUNCTIONAL).orElseThrow().delta();
        assertThat(delta.acceptedIssues()).containsExactly("io.acme.orders.OrdersTest.refunds");
        assertThat(delta.newIssues()).contains("io.acme.orders.OrdersTest.pays");
        assertThat(report.notes()).containsExactly(
                "accepted until 2026-10-31, expired — counted as new: functional io.acme.orders.OrdersTest.pays",
                "accepted but not seen in this run — delete it once that holds: functional io.acme.gone.T.x");
    }

    @Test
    void aBudgetLetsAFewNewIssuesThroughAndAMinimumScoreFailsWhateverTheStatus() throws IOException {
        junit();

        assertThatCode(() -> runner(null, Map.of(), Map.of(QualityDimension.FUNCTIONAL, new GateProperties(null, 3)))
                .assertAcceptable()).as("3 new functional issues, budget 3").doesNotThrowAnyException();
        assertThatThrownBy(() -> runner(null, Map.of(), Map.of(QualityDimension.FUNCTIONAL,
                new GateProperties(null, 2))).assertAcceptable())
                .hasMessageStartingWith("Quality gate failed on functional (5 tests: 2 passed, 3 failed");
        assertThatCode(() -> runner(null, Map.of(), Map.of(QualityDimension.FUNCTIONAL, new GateProperties(null, 3),
                QualityDimension.HYGIENE, new GateProperties(101, null))).assertAcceptable())
                .as("hygiene is not measured here: that is for required").doesNotThrowAnyException();
        assertThatThrownBy(() -> runner(null, Map.of(), Map.of(QualityDimension.FUNCTIONAL, new GateProperties(null, 3),
                QualityDimension.STABILITY, new GateProperties(90, 0))).assertAcceptable())
                .hasMessageContaining("stability (score 80 below the gate's 90)")
                .hasMessageContaining("stability (1 new issues, budget 0)");
    }

    @Test
    void theExportsCarryTheSameNumbers() throws IOException {
        junit();
        QualityReport report = runner(null, Map.of("orders", new ComponentProperties("team-orders",
                List.of("io.acme.orders.*"))), Map.of()).run();
        Path output = Path.of(report.reportJson()).getParent();

        assertThat(report.labels()).containsEntry("environment", "staging");
        assertThat(Files.readString(output.resolve("quality.md"))).contains("- labels: ")
                .contains("environment=staging");
        assertThat(Files.readString(output.resolve("quality-summary.md")))
                .startsWith("### Quality: FAIL")
                .contains("| functional | FAIL | 40 | `io.acme.orders.OrdersTest.pays`, "
                        + "`io.acme.orders.OrdersTest.refunds`, `io.acme.billing.BillsTest.bills` |")
                .contains("- **orders** (team-orders): 2 new, FAIL")
                .contains("Full report: `quality.md`.");
        String metrics = Files.readString(output.resolve("quality.prom"));
        assertThat(metrics)
                .contains("testforge_quality_score{dimension=\"functional\"} 40\n")
                .contains("testforge_quality_status{dimension=\"functional\",testforge_quality_status=\"FAIL\"} 1\n")
                .contains("testforge_quality_status{dimension=\"functional\",testforge_quality_status=\"PASS\"} 0\n")
                .contains("testforge_quality_issues{dimension=\"functional\",kind=\"new\"} 3\n")
                .contains("testforge_quality_component_new_issues{component=\"orders\"} 2\n")
                .contains("team_name=\"qa \\\"core\\\"\"")
                .endsWith("# EOF\n");
        assertThat(metrics.lines().filter(line -> !line.startsWith("#")))
                .allMatch(line -> line.matches("[a-z_]+(\\{.*})? -?\\d+"));
    }

    @Test
    void theSchemaDescribesEveryFieldQualityJsonHas() throws IOException {
        junit();
        QualityReport report = runner(null, Map.of("orders", new ComponentProperties(null, List.of("*"))), Map.of())
                .run();
        JsonNode schema = mapper.readTree(Path.of(report.reportJson()).getParent().resolve("quality.schema.json")
                .toFile());
        JsonNode json = mapper.readTree(Path.of(report.reportJson()).toFile());

        assertThat(json.path("formatVersion").asInt()).isEqualTo(QualityReport.FORMAT_VERSION);
        assertThat(names(json)).as("a field quality.json gains belongs in the schema in the same change")
                .isSubsetOf(names(schema.path("properties")));
        assertThat(names(json.path("dimensions").get(0)))
                .isSubsetOf(names(schema.path("$defs").path("dimension").path("properties")));
        assertThat(names(json.path("components").get(0)))
                .isSubsetOf(names(schema.path("$defs").path("component").path("properties")));
        json.path("dimensions").forEach(dimension -> {
            if (!dimension.path("delta").isNull()) {
                assertThat(names(dimension.path("delta"))).isSubsetOf(names(schema.path("$defs").path("dimension")
                        .path("properties").path("delta").path("oneOf").get(1).path("properties")));
            }
        });
        schema.path("required").forEach(field -> assertThat(json.has(field.asText())).as(field.asText()).isTrue());
    }

    private static List<String> names(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
