package io.testforge.smoke;

import static org.assertj.core.api.Assertions.assertThat;

import io.testforge.artifact.ArtifactSink;
import io.testforge.artifact.TestArtifact;
import io.testforge.reporting.ArtifactRunLayout;
import io.testforge.reporting.RunArtifactSink;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * The reporting module as an external consumer sees it: resolved from the published
 * artifact, auto-configured from the JAR's own metadata.
 */
class PublishedReportingSmokeTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(SmokeTestApplication.class);

    @Test
    void disabledByDefault_injectedSinkIsNoOp() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(ArtifactSink.class);
            ArtifactSink sink = context.getBean(ArtifactSink.class);
            assertThat(sink).isSameAs(ArtifactSink.NO_OP);
            assertThat(context).doesNotHaveBean(RunArtifactSink.class);
            assertThat(context).doesNotHaveBean(ArtifactRunLayout.class);
        });
    }

    @Test
    void enabled_wiresRealSinkAndWritingProducesFile() {
        contextRunner
                .withPropertyValues(
                        "forge.reporting.artifacts.enabled=true",
                        "forge.reporting.artifacts.dir=build/published-reporting-smoke",
                        "forge.reporting.artifacts.run-id=smoke-run"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(ArtifactRunLayout.class);
                    assertThat(context).hasSingleBean(ArtifactSink.class);

                    ArtifactSink sink = context.getBean(ArtifactSink.class);
                    assertThat(sink).isInstanceOf(RunArtifactSink.class);

                    TestArtifact artifact = sink.write(
                            "smoke-module",
                            "diagnostics",
                            "smoke-test.txt",
                            "text/plain",
                            "smoke artifact content"
                    );

                    assertThat(artifact).isNotNull();
                    Path artifactFile = artifact.file();
                    assertThat(artifactFile).exists();
                    assertThat(artifactFile).startsWith(Path.of("build/published-reporting-smoke/smoke-run"));
                    assertThat(Files.readString(artifactFile)).isEqualTo("smoke artifact content");
                });
    }
}
