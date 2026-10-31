package io.testforge.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** A suite caches several Spring contexts; one run must still produce one complete index. */
class RunIndexAcrossContextsTest {

    @TempDir
    Path base;

    @AfterEach
    void forget() {
        RunIndex.reset();
    }

    private static ArtifactReportingLifecycle context(ArtifactRunLayout layout, String source) {
        RunArtifactSink sink = new RunArtifactSink(layout);
        sink.write(source, "diagnostics", source + ".txt", "text/plain", "data");
        ArtifactReportingLifecycle lifecycle = new ArtifactReportingLifecycle(sink, layout,
                new ArtifactManifestWriter(), new ArtifactSummaryWriter());
        lifecycle.start();
        return lifecycle;
    }

    @Test
    void theLastContextToCloseIndexesEveryContextsArtifacts() throws Exception {
        ArtifactRunLayout layout = new ArtifactRunLayout(base, "shared-run");
        ArtifactReportingLifecycle first = context(layout, "module-http");
        ArtifactReportingLifecycle second = context(new ArtifactRunLayout(base, "shared-run"), "module-db");

        first.stop();
        second.stop();

        String manifest = Files.readString(layout.runRoot().resolve("manifest.json"));
        assertThat(manifest).contains("\"artifactCount\" : 2").contains("module-http.txt").contains("module-db.txt");
        assertThat(Files.readString(layout.runRoot().resolve("summary.md")))
                .contains("module-http.txt").contains("module-db.txt");
    }

    @Test
    void separateRunsStaySeparate() throws Exception {
        context(new ArtifactRunLayout(base, "run-a"), "module-http").stop();
        context(new ArtifactRunLayout(base, "run-b"), "module-db").stop();

        assertThat(Files.readString(base.resolve("run-b/manifest.json")))
                .contains("\"artifactCount\" : 1").doesNotContain("module-http");
    }

    @Test
    void contextsWithoutAConfiguredRunIdShareTheJvmsRun() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(TestForgeReportingAutoConfiguration.class))
                .withPropertyValues("forge.reporting.artifacts.enabled=true",
                        "forge.reporting.artifacts.dir=" + base);
        String[] ids = new String[2];

        runner.run(context -> ids[0] = context.getBean(ArtifactRunLayout.class).runId());
        runner.run(context -> ids[1] = context.getBean(ArtifactRunLayout.class).runId());

        assertThat(ids[0]).isEqualTo(ids[1]).isEqualTo(ArtifactRunLayout.jvmRunId());
    }
}
