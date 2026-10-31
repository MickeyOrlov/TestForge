package io.testforge.example;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.platform.testkit.engine.EngineTestKit;

/**
 * A mobile sign-in failing in {@code @BeforeEach} leaves the same evidence as a
 * failing test: the screen, its hierarchy and the session summary.
 */
class AppiumLifecycleFailureExampleTest {

    @Test
    void aFailingBeforeEachLeavesTheScreenAndTheSession() throws Exception {
        EngineTestKit.engine("junit-jupiter")
                .selectors(selectClass(AppiumSignInFailureTestkitFixture.class))
                .execute()
                .testEvents()
                .assertStatistics(stats -> stats.failed(1));

        Path dir = Path.of("build/appium-artifacts/lifecycle",
                "AppiumSignInFailureTestkitFixture_opensTheDashboard_android-local");
        assertThat(dir.resolve("screenshot.png")).exists();
        assertThat(Files.readString(dir.resolve("page-source.xml"))).contains("Wrong password");
        assertThat(Files.readString(dir.resolve("session.md"))).contains("- device: android-local");
    }
}
