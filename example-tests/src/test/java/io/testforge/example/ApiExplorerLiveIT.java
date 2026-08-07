package io.testforge.example;

import static org.assertj.core.api.Assertions.assertThat;

import io.testforge.api.explorer.ApiExplorerReport;
import io.testforge.api.explorer.ApiExplorerRunner;
import io.testforge.api.explorer.ExplorerOutcome;
import io.testforge.api.explorer.ObservationSummary;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The same pipeline against a real public API, as an opt-in.
 *
 * <p>Excluded from the default build twice over: the {@code live-api} tag is
 * filtered out of {@code test}, and the class only runs when
 * {@code TESTFORGE_LIVE_API_BASE_URL} is set. Nothing here can turn a normal
 * offline build red, and nothing here reaches the network unless somebody asked
 * for it:
 *
 * <pre>
 * TESTFORGE_LIVE_API_BASE_URL=https://restful-booker.herokuapp.com \
 *   ./gradlew :example-tests:liveApiTest
 * </pre>
 *
 * <p>It deliberately does not assert that the run is healthy. A live API is
 * allowed to disagree with its document — recording that disagreement is the
 * feature, not a test failure.
 */
@Tag("live-api")
@EnabledIfEnvironmentVariable(named = "TESTFORGE_LIVE_API_BASE_URL", matches = ".+")
@SpringBootTest(properties = {
        "forge.api-explorer.enabled=true",
        "forge.api-explorer.output-dir=build/api-explorer/live",
        "forge.http.base-url=${TESTFORGE_LIVE_API_BASE_URL}",
        "forge.api-discovery.specs.booker.location=classpath:/openapi/restful-booker.yaml"
})
class ApiExplorerLiveIT {

    @Autowired
    ApiExplorerRunner explorer;

    @Test
    void exploresAPublicApiAndRecordsWhatItActuallyReturns() throws IOException {
        ApiExplorerReport report = explorer.run();

        assertThat(report.specs()).singleElement().satisfies(spec -> {
            assertThat(spec.operations()).isEqualTo(3);
            // whatever the service says today, every operation must be accounted for
            assertThat(spec.passed() + spec.contractMismatch() + spec.failedCalls() + spec.skipped())
                    .isEqualTo(spec.operations());
            assertThat(spec.failedCalls())
                    .describedAs("the API should be reachable when this test is enabled")
                    .isZero();
        });

        assertThat(report.specs().getFirst().observations())
                .extracting(ObservationSummary::outcome)
                .doesNotContain(ExplorerOutcome.FAILED);

        assertThat(Files.readString(Path.of(report.reportMarkdown())))
                .contains("# API Explorer Report")
                .contains("booker");
    }
}
