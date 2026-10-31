package io.testforge.example;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.platform.testkit.engine.EngineTestKit;

/**
 * What a failing UI test leaves behind, on a real browser: a screenshot, a
 * trace, and {@code browser.md} — the failed request, the console error and
 * the uncaught page error, as text to read before opening the trace.
 *
 * <p>Tagged {@code browser}: run with {@code ./gradlew :example-tests:browsersTest}.
 */
@Tag("browser")
class PlaywrightFailureArtifactsIT {

    @Test
    void aFailingUiTestLeavesTheBrowsersSideOfTheStory() throws Exception {
        EngineTestKit.engine("junit-jupiter")
                .selectors(selectClass(PlaywrightFailureTestkitFixture.class))
                .execute()
                .testEvents()
                .assertStatistics(stats -> stats.failed(1));

        Path dir = Path.of("build/playwright-artifacts-fixture/PlaywrightFailureTestkitFixture-showsTheTotal");
        assertThat(dir.resolve("screenshot.png")).exists();
        assertThat(dir.resolve("trace.zip")).exists();
        assertThat(Files.readString(dir.resolve("browser.md")))
                .contains("- title: Broken checkout")
                .contains("/api/cart?session=*** -> 500")
                .contains("- error: cart failed with 500")
                .contains("cannot render the total")
                .doesNotContain("s3cret");
    }
}
