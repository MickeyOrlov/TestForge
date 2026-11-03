package io.testforge.a11y;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.microsoft.playwright.Page;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class A11yTest {
    @TempDir Path dir;
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-03T12:00:00Z"), ZoneOffset.UTC);
    private A11yProperties properties(List<A11yProperties.Accepted> accepted) {
        return new A11yProperties(true, null, null, null, dir.resolve("baseline.json").toString(),
                dir.resolve("evidence").toString(), List.of("/orders/{id}"), accepted, null);
    }
    private A11yEngine.Node node(String rule, String impact, String selector) {
        return new A11yEngine.Node(rule, impact, List.of("wcag2a"), selector,
                "https://dequeuniversity.com/rules/axe/4.11/" + rule);
    }
    private A11y scanner(A11yProperties properties, List<A11yEngine.Node> nodes) {
        return new A11y(properties, (page, p, i, e) -> new A11yEngine.Scan("4.11.1", nodes, List.of()), null, clock);
    }
    @Test void defaultIsInertAndEnablingNeverCreatesABrowser() {
        var c = new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(TestForgeA11yAutoConfiguration.class));
        c.run(ctx -> assertThat(ctx).doesNotHaveBean(A11y.class));
        c.withPropertyValues("forge.a11y.enabled=true").run(ctx -> assertThat(ctx).hasSingleBean(A11y.class));
        assertThatThrownBy(() -> new A11yProperties(true, null, Map.of("image-alt", ""), null, null, null, null, null, null))
                .hasMessageContaining("reason");
    }
    @Test void baselineAllowsKnownFindingsButNewSelectorsAndEscalatedImpactFail() {
        var props = properties(List.of());
        var original = scanner(props, List.of(node("image-alt", "serious", "#old")));
        var report = original.check(null, "/orders/{id}");
        assertThat(report.passed()).isFalse();
        assertThat(dir.resolve("baseline.json")).doesNotExist();
        original.writeBaseline(report);
        assertThat(original.check(null, "/orders/{id}").passed()).isTrue();
        assertThat(scanner(props, List.of(node("image-alt", "critical", "#old"))).check(null, "/orders/{id}").passed()).isFalse();
        var newPage = scanner(props, List.of(node("image-alt", "serious", "#new")));
        assertThatThrownBy(() -> newPage.assertNoNewViolations(null, "/orders/{id}")).isInstanceOf(AssertionError.class);
    }
    @Test void acceptanceExpiresAndBelowThresholdFindingsRemainVisible() {
        var node = node("image-alt", "serious", "#one");
        String key = scanner(properties(List.of()), List.of(node)).check(null, "/orders/{id}").violations().getFirst().key();
        var active = new A11yProperties.Accepted(key, "A11Y-7", LocalDate.parse("2026-11-03"));
        assertThat(scanner(properties(List.of(active)), List.of(node)).check(null, "/orders/{id}").passed()).isTrue();
        var expired = new A11yProperties.Accepted(key, "A11Y-7", LocalDate.parse("2026-11-02"));
        var baseline = scanner(properties(List.of()), List.of(node));
        baseline.writeBaseline(baseline.check(null, "/orders/{id}"));
        assertThat(scanner(properties(List.of(expired)), List.of(node)).check(null, "/orders/{id}").passed()).isFalse();
        var mild = scanner(properties(List.of()), List.of(node("image-alt", "minor", "#one"))).check(null, "/orders/{id}");
        assertThat(mild.passed()).isTrue(); assertThat(mild.violations()).hasSize(1);
    }
    @Test void pageTemplatesAndEvidenceDoNotContainQueryFormValuesOrRawMarkup() throws Exception {
        Page page = mock(Page.class); when(page.url()).thenReturn("http://localhost/orders/customer-secret?token=private-token");
        var scanner = scanner(properties(List.of()), List.of(node("image-alt", "serious", "[data-x='form-secret']")));
        var report = scanner.check(page);
        assertThat(report.pageKey()).isEqualTo("/orders/{id}");
        assertThat(Files.readString(Path.of(report.directory(), "a11y.json")))
                .doesNotContain("customer-secret", "private-token", "form-secret", "<img");
        assertThat(report.violations().getFirst().selector()).contains("redacted");
        assertThatThrownBy(() -> scanner.check(page, "/checkout?secret=x")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void malformedOrDifferentPolicyBaselineFailsBeforeEngineAndIncompleteCannotPass() throws Exception {
        var props = properties(List.of()); Files.writeString(dir.resolve("baseline.json"), "{}");
        A11yEngine never = (page, p, i, e) -> { throw new AssertionError("Must not scan"); };
        assertThatThrownBy(() -> new A11y(props, never, null).check(null, "/orders/{id}"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("baseline");
        Files.delete(dir.resolve("baseline.json"));
        var incomplete = new A11y(props, (page, p, i, e) -> new A11yEngine.Scan("4.11.1", List.of(),
                List.of(node("color-contrast", null, "#manual"))), null);
        var result = incomplete.check(null, "/orders/{id}");
        assertThat(result.passed()).isFalse();
        assertThatThrownBy(() -> incomplete.writeBaseline(result)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void promotingAnotherPageRetainsOtherPagesAndResolvedFindingsDisappear() throws Exception {
        var scanner = scanner(properties(List.of()), List.of(node("image-alt", "serious", "#same")));
        scanner.writeBaseline(scanner.check(null, "/orders/{id}"));
        scanner.writeBaseline(scanner.check(null, "/checkout"));
        assertThat(scanner.check(null, "/orders/{id}").passed()).isTrue();
        assertThat(scanner.check(null, "/checkout").passed()).isTrue();
        var empty = scanner(properties(List.of()), List.of());
        empty.writeBaseline(empty.check(null, "/checkout"));
        assertThat(scanner.check(null, "/checkout").passed()).isFalse();
        assertThat(scanner.check(null, "/orders/{id}").passed()).isTrue();
    }
    @Test void aDifferentScanPolicyCannotReuseTheBaseline() {
        var scanner = scanner(properties(List.of()), List.of(node("image-alt", "serious", "#one")));
        scanner.writeBaseline(scanner.check(null, "/orders/{id}"));
        assertThatThrownBy(() -> scanner.check(null, "/orders/{id}", List.of("main"), List.of()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("baseline");
    }

    @Test void runIndexCopiesRemainReadableWithoutTheOriginalOutputDirectory() throws Exception {
        java.util.List<io.testforge.artifact.TestArtifact> artifacts = new java.util.ArrayList<>();
        io.testforge.artifact.ArtifactSink sink = new io.testforge.artifact.ArtifactSink() {
            public Path directoryFor(String source) { return dir.resolve("indexed"); }
            public void register(io.testforge.artifact.TestArtifact artifact) { artifacts.add(artifact); }
            public io.testforge.artifact.TestArtifact write(String s, String c, String n, String m, String text) {
                throw new AssertionError("Allocate collision-safe files");
            }
        };
        var scanner = new A11y(properties(List.of()), (page, p, i, e) -> new A11yEngine.Scan("4.11.1",
                List.of(node("image-alt", "serious", "#one")), List.of()), sink, clock);
        scanner.check(null, "/orders/{id}"); scanner.check(null, "/checkout");
        assertThat(artifacts).hasSize(4);
        assertThat(artifacts).allSatisfy(a -> assertThat(a.file()).isRegularFile());
        assertThat(Files.readString(dir.resolve("indexed/a11y.json"))).contains("/orders/{id}");
        assertThat(Files.readString(dir.resolve("indexed/a11y-2.json"))).contains("/checkout");
    }
}
