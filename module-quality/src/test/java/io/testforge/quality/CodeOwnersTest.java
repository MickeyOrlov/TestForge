package io.testforge.quality;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.testforge.artifact.ArtifactSink;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Owners from CODEOWNERS for issues that point at a file, and per-run labels kept out of quality.prom. */
class CodeOwnersTest {

    @TempDir
    Path dir;

    private void file(String path, String content) throws IOException {
        Path file = dir.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private CodeOwners repository() throws IOException {
        file(".github/CODEOWNERS", """
                # last match wins
                *                                   @org/everyone
                /src/main/java/io/acme/payments/    @org/payments   # the payment core
                *Test.java                          @org/qa
                /legacy/
                """);
        file("src/main/java/io/acme/payments/Prices.java", "class Prices {}");
        file("src/test/java/io/acme/orders/OrdersTest.java", "class OrdersTest {}");
        file("legacy/src/main/java/io/old/Old.java", "class Old {}");
        file("build/generated/src/main/java/io/acme/payments/Prices.java", "generated copy");
        return CodeOwners.read(dir.resolve(".github/CODEOWNERS"));
    }

    @Test
    void anIssueThatPointsAtAFileGoesToTheLastMatchingRule() throws IOException {
        CodeOwners owners = repository();

        assertThat(owners.ownersOf("io.acme.orders.OrdersTest.pays()")).contains("@org/qa");
        assertThat(owners.ownersOf("io.acme.orders.OrdersTest$Refunds.partial(int)[2]")).contains("@org/qa");
        assertThat(owners.ownersOf("io.acme.payments.Prices.discount ConditionalsBoundaryMutator"))
                .contains("@org/payments");
        assertThat(owners.ownersOf("src/main/java/io/acme/payments/Prices.java")).contains("@org/payments");
        assertThat(owners.ownersOf("io.old.Old.run()")).as("a rule without owners unowns").isEmpty();
        assertThat(owners.ownersOf("demo GET /orders/{id}")).as("an operation points at no file").isEmpty();
        assertThat(owners.ownersOf("io.acme.Missing.x()")).isEmpty();
    }

    @Test
    void patternsFollowGitHub() {
        assertThat(CodeOwners.pattern("*.java").matcher("a/b/C.java").matches()).isTrue();
        assertThat(CodeOwners.pattern("/docs/*").matcher("docs/a.md").matches()).isTrue();
        assertThat(CodeOwners.pattern("/docs/*").matcher("docs/a/b.md").matches()).as("* stays in one directory")
                .isFalse();
        assertThat(CodeOwners.pattern("apps/").matcher("x/apps/a.java").matches()).as("unanchored directory")
                .isTrue();
        assertThat(CodeOwners.pattern("/apps/").matcher("x/apps/a.java").matches()).isFalse();
        assertThat(CodeOwners.pattern("src/**/payments").matcher("src/main/java/payments/P.java").matches()).isTrue();
    }

    @Test
    void unmatchedIssuesFallToTheirOwnersAndPerRunLabelsStayOutOfTheMetrics() throws IOException {
        repository();
        file("results/TEST-s.xml", "<testsuite name=\"s\"><testcase classname=\"io.acme.orders.OrdersTest\" "
                + "name=\"pays()\"><failure message=\"x\"/></testcase><testcase classname=\"io.acme.orders.OrdersTest\" "
                + "name=\"ok()\"/></testsuite>");
        ObjectMapper mapper = new ObjectMapper();
        java.util.function.Function<Boolean, QualityReport> run = runIds -> new QualityRunner(new QualityProperties(
                true, List.of(dir.resolve("results").toString()), dir.resolve("artifacts").toString(), null,
                dir.resolve("quality").toString(), 0.5, null, dir.resolve("baseline.json").toString(), null, null,
                null, null, null, null, Map.of("revision", "abc123", "environment", "staging"), null, null, null, null,
                null, null, dir.resolve(".github/CODEOWNERS").toString(),
                new QualityProperties.MetricsProperties(runIds)), mapper, ArtifactSink.NO_OP).run();

        QualityReport report = run.apply(false);
        assertThat(report.components()).singleElement().satisfies(component -> {
            assertThat(component.name()).isEqualTo("@org/qa");
            assertThat(component.owner()).isEqualTo("@org/qa");
            assertThat(component.issues()).containsExactly("functional io.acme.orders.OrdersTest.pays()");
        });
        Path metrics = Path.of(report.reportJson()).getParent().resolve("quality.prom");
        assertThat(Files.readString(metrics)).contains("environment=\"staging\"").doesNotContain("abc123");
        run.apply(true);
        assertThat(Files.readString(metrics)).contains("revision=\"abc123\"");
    }
}
