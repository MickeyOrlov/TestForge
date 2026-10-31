package io.testforge.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import io.testforge.artifact.TestArtifact;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArtifactSummaryFailedTestsTest {

    @TempDir
    Path base;

    private TestArtifact artifact(ArtifactRunLayout layout, String source, String category, String name,
                                  Map<String, String> metadata) {
        return new TestArtifact(source, category, name, layout.runRoot().resolve(source).resolve(name),
                "text/markdown", Instant.parse("2026-01-01T10:00:00Z"), metadata);
    }

    @Test
    void eachFailedTestListsItsEvidenceFromEveryModule() throws Exception {
        ArtifactRunLayout layout = new ArtifactRunLayout(base, "run");
        List<TestArtifact> artifacts = List.of(
                artifact(layout, "module-http", "failed-test-http", "OrdersTest-pays.md", Map.of()),
                artifact(layout, "module-db", "failed-test-sql", "OrdersTest-pays.md", Map.of()),
                artifact(layout, "module-kafka", "failed-test-kafka", "OrdersTest-pays.md", Map.of()),
                artifact(layout, "module-web-playwright", "ui-failure", "OrdersTest-pays/screenshot.png",
                        Map.of("test", "pays()", "testKey", "OrdersTest-pays")),
                artifact(layout, "module-http", "failed-test-http", "CartTest-adds.md", Map.of()),
                artifact(layout, "module-flow", "flow-path", "flow-path-checkout.txt", Map.of()));

        new ArtifactSummaryWriter().writeSummary(layout, artifacts, List.of());
        String summary = Files.readString(layout.runRoot().resolve("summary.md"));

        assertThat(summary).contains("""
                ## Failed tests

                ### CartTest-adds

                - module-http (failed-test-http): `module-http/CartTest-adds.md`

                ### OrdersTest-pays

                - module-db (failed-test-sql): `module-db/OrdersTest-pays.md`
                - module-http (failed-test-http): `module-http/OrdersTest-pays.md`
                - module-kafka (failed-test-kafka): `module-kafka/OrdersTest-pays.md`
                - module-web-playwright (ui-failure): `module-web-playwright/OrdersTest-pays/screenshot.png`
                """);
        assertThat(summary.indexOf("## Failed tests")).isLessThan(summary.indexOf("## Diagnostics by Source"));
    }

    @Test
    void aRunWithoutFailedTestsHasNoSuchSection() throws Exception {
        ArtifactRunLayout layout = new ArtifactRunLayout(base, "green");

        new ArtifactSummaryWriter().writeSummary(layout,
                List.of(artifact(layout, "module-flow", "flow-coverage", "flow-coverage.md", Map.of("healthy", "true"))),
                List.of());

        assertThat(Files.readString(layout.runRoot().resolve("summary.md"))).doesNotContain("## Failed tests");
    }
}
