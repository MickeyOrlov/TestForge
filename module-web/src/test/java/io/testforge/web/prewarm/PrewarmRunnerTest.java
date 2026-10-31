package io.testforge.web.prewarm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.testforge.artifact.ArtifactSink;
import io.testforge.artifact.TestArtifact;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Offline: a fake engine answers instead of a browser. */
class PrewarmRunnerTest {

    private final Map<String, Object> written = new java.util.LinkedHashMap<>();

    private final ArtifactSink sink = new ArtifactSink() {
        @Override
        public java.nio.file.Path directoryFor(String source) {
            return java.nio.file.Path.of("build", "unused");
        }

        @Override
        public void register(TestArtifact artifact) {
        }

        @Override
        public TestArtifact write(String source, String category, String name, String mediaType, String content) {
            written.put(source + "/" + category + "/" + name, content);
            return null;
        }
    };

    /** Answers each URL with the visit listed for it, records what it was asked. */
    private static PrewarmEngine engine(Map<String, PrewarmVisit> answers, List<String> asked) {
        return properties -> new PrewarmEngine.Session() {
            @Override
            public PrewarmVisit visit(String url) {
                asked.add(url);
                return answers.get(url);
            }

            @Override
            public void close() {
            }
        };
    }

    private static PrewarmProperties properties(boolean failWhenUnreachable, String... urls) {
        return new PrewarmProperties(true, List.of(urls), true, Duration.ofSeconds(5), failWhenUnreachable);
    }

    @Test
    void everyPageIsVisitedOnceAndWhatItAnsweredIsReported() {
        List<String> asked = new ArrayList<>();
        PrewarmRunner runner = new PrewarmRunner(properties(false, "https://s.test/", "https://s.test/app"),
                engine(Map.of(
                        "https://s.test/", new PrewarmVisit("https://s.test/", 200, "https://s.test/", 812, null),
                        "https://s.test/app", new PrewarmVisit("https://s.test/app", 200, "https://s.test/sign-in", 95, null)),
                        asked), sink, new java.util.HashSet<>());

        PrewarmReport report = runner.runOnce();

        assertThat(asked).containsExactly("https://s.test/", "https://s.test/app");
        assertThat(report.visits()).extracting(PrewarmVisit::describe).containsExactly(
                "https://s.test/ -> 200 in 812 ms",
                "https://s.test/app -> 200 at https://s.test/sign-in in 95 ms");
        assertThat(runner.lastReport()).isSameAs(report);
        assertThat((String) written.get("module-web/prewarm/prewarm.md"))
                .contains("- pages: 2")
                .contains("- reached: 2")
                .contains("| https://s.test/app | 200 | https://s.test/sign-in | 95 | — |");
    }

    @Test
    void anUnreachableEnvironmentOnlyWarnsByDefault() {
        PrewarmRunner runner = new PrewarmRunner(properties(false, "https://s.test/"),
                engine(Map.of("https://s.test/", new PrewarmVisit("https://s.test/", 502, "https://s.test/", 30, null)),
                        new ArrayList<>()), sink, new java.util.HashSet<>());

        PrewarmReport report = runner.runOnce();

        assertThat(report.environmentReached()).isFalse();
    }

    @Test
    void failWhenUnreachableStopsTheSuiteWhenNotOnePageAnswers() {
        PrewarmRunner runner = new PrewarmRunner(properties(true, "https://s.test/", "https://s.test/app"),
                engine(Map.of(
                        "https://s.test/", new PrewarmVisit("https://s.test/", 503, "https://s.test/", 30, null),
                        "https://s.test/app", new PrewarmVisit("https://s.test/app", null, null, 40000,
                                "Timeout 40000ms exceeded")), new ArrayList<>()), sink, new java.util.HashSet<>());

        assertThatThrownBy(runner::runOnce)
                .isInstanceOf(PrewarmException.class)
                .hasMessageContaining("environment looks down")
                .hasMessageContaining("https://s.test/ -> 503 in 30 ms")
                .hasMessageContaining("https://s.test/app -> failed: Timeout 40000ms exceeded");
        // the evidence is published before the suite stops
        assertThat(written).containsKey("module-web/prewarm/prewarm.md");
    }

    @Test
    void onePageAnsweringIsEnoughEvenWithTheFlagOn() {
        PrewarmRunner runner = new PrewarmRunner(properties(true, "https://s.test/", "https://s.test/app"),
                engine(Map.of(
                        "https://s.test/", new PrewarmVisit("https://s.test/", 401, "https://s.test/", 30, null),
                        "https://s.test/app", new PrewarmVisit("https://s.test/app", 500, "https://s.test/app", 30, null)),
                        new ArrayList<>()), sink, new java.util.HashSet<>());

        assertThat(runner.runOnce().environmentReached()).isTrue();
    }

    @Test
    void anEngineThatCannotStartSaysNothingAboutTheEnvironment() {
        PrewarmEngine broken = properties -> {
            throw new IllegalStateException("Executable doesn't exist at /ms-playwright/chromium\nmore");
        };
        PrewarmRunner runner = new PrewarmRunner(properties(true, "https://s.test/"), broken, sink, new java.util.HashSet<>());

        PrewarmReport report = runner.runOnce();

        assertThat(report.skipped()).isEqualTo("could not start: Executable doesn't exist at /ms-playwright/chromium");
        assertThat((String) written.get("module-web/prewarm/prewarm.md")).contains("Skipped: could not start");
    }

    @Test
    void itRunsOncePerGuardAndSaysWhyItSkipped() {
        java.util.Set<String> once = new java.util.HashSet<>();
        List<String> asked = new ArrayList<>();
        PrewarmEngine engine = engine(Map.of("https://s.test/",
                new PrewarmVisit("https://s.test/", 200, "https://s.test/", 1, null)), asked);

        new PrewarmRunner(properties(false, "https://s.test/"), engine, sink, once).runOnce();
        PrewarmReport second = new PrewarmRunner(properties(false, "https://s.test/"), engine, sink, once).runOnce();

        assertThat(asked).hasSize(1);
        assertThat(second.skipped()).isEqualTo("already ran in this JVM");
        assertThat(new PrewarmRunner(new PrewarmProperties(true, List.of(), null, null), engine, sink, new java.util.HashSet<>())
                .runOnce().skipped()).isEqualTo("no URLs configured");
    }

    @Test
    void aSecondContextWarmsOnlyThePagesTheFirstDidNot() {
        java.util.Set<String> visited = new java.util.HashSet<>();
        List<String> asked = new ArrayList<>();
        PrewarmEngine engine = engine(Map.of(
                "https://s.test/", new PrewarmVisit("https://s.test/", 200, "https://s.test/", 1, null),
                "https://other.test/", new PrewarmVisit("https://other.test/", 200, "https://other.test/", 1, null)),
                asked);

        new PrewarmRunner(properties(false, "https://s.test/"), engine, sink, visited).runOnce();
        PrewarmReport second = new PrewarmRunner(properties(false, "https://s.test/", "https://other.test/"),
                engine, sink, visited).runOnce();

        assertThat(asked).containsExactly("https://s.test/", "https://other.test/");
        assertThat(second.visits()).extracting(PrewarmVisit::url).containsExactly("https://other.test/");
    }

    @Test
    void aNormalizedEchoOfTheSamePageIsNotARedirect() {
        assertThat(new PrewarmVisit("https://s.test", 200, "https://s.test/", 1, null).redirected()).isFalse();
        assertThat(new PrewarmVisit("https://S.test:443/a", 200, "https://s.test/a", 1, null).redirected()).isFalse();
        assertThat(new PrewarmVisit("https://s.test/a", 200, "https://s.test/sign-in", 1, null).redirected()).isTrue();
    }

    @Test
    void errorsNeverQuoteTheCredentialsOfTheUrlTheyFailedOn() {
        String url = "https://qa:s3cret@staging.example.test/app?token=abc";

        String scrubbed = UrlRedactor.scrub("net::ERR_NAME_NOT_RESOLVED at " + url + "\nCall log:", url);

        assertThat(scrubbed).doesNotContain("s3cret").doesNotContain("token=abc")
                .contains("https://staging.example.test/app?token=***");
        assertThat(UrlRedactor.scrub("navigating to qa:s3cret@staging.example.test", url))
                .isEqualTo("navigating to staging.example.test");
    }

    @Test
    void urlsLoseCredentialsAndQueryValuesBeforeTheyAreLoggedOrReported() {
        assertThat(UrlRedactor.redact("https://qa:s3cret@staging.example.test/app?token=abc&lang=en"))
                .isEqualTo("https://staging.example.test/app?token=***&lang=***");
        assertThat(UrlRedactor.redact("https://staging.example.test/")).isEqualTo("https://staging.example.test/");
    }
}
