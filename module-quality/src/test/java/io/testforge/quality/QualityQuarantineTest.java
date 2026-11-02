package io.testforge.quality;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.testforge.artifact.ArtifactSink;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Assertion vs error failures, failure fingerprints, and accepted entries pinned to one. */
class QualityQuarantineTest {

    @TempDir
    Path dir;

    private final Clock today = Clock.fixed(Instant.parse("2026-11-01T10:00:00Z"), ZoneOffset.UTC);

    private QualityRunner runner(List<String> accepted) {
        return new QualityRunner(new QualityProperties(true, List.of(dir.resolve("results").toString()),
                dir.resolve("artifacts").toString(), null, dir.resolve("quality").toString(), 0.5, null,
                dir.resolve("baseline.json").toString(), accepted), new ObjectMapper(), ArtifactSink.NO_OP, today);
    }

    private void junit(String gatewayFailure) throws IOException {
        Path path = dir.resolve("results/TEST-s.xml");
        Files.createDirectories(path.getParent());
        Files.writeString(path, "<testsuite name=\"s\">"
                + "<testcase classname=\"io.acme.PayTest\" name=\"pays\">" + gatewayFailure + "</testcase>"
                + "<testcase classname=\"io.acme.PayTest\" name=\"refunds\">"
                + "<failure message=\"expected: 200 but was: 500\" type=\"org.opentest4j.AssertionFailedError\">"
                + "org.opentest4j.AssertionFailedError: expected: 200 but was: 500\n"
                + "\tat org.junit.jupiter.api.AssertionUtils.fail(AssertionUtils.java:55)\n"
                + "\tat io.acme.PayTest.refunds(PayTest.java:40)\n</failure></testcase>"
                + "<testcase classname=\"io.acme.PayTest\" name=\"ok\"/>"
                + "</testsuite>");
    }

    private static String timeout(int line) {
        return "<error message=\"Connection refused\" type=\"java.net.ConnectException\">"
                + "java.net.ConnectException: Connection refused\n"
                + "\tat java.base/sun.nio.ch.Net.connect0(Native Method)\n"
                + "\tat io.acme.Gateway.lambda$call$0(Gateway.java:" + line + ")\n"
                + "\tat io.acme.Gateway$$Lambda/0x0000012345.apply(Unknown Source)\n"
                + "\tat io.acme.PayTest.pays(PayTest.java:" + (line + 7) + ")\n</error>";
    }

    @Test
    void failuresAreSplitIntoAssertionsAndErrorsWithAFingerprintEach() throws IOException {
        junit(timeout(12));
        QualityDimension functional = runner(null).run().dimension(QualityDimension.FUNCTIONAL).orElseThrow();
        String fingerprint = JunitResults.fingerprint("java.net.ConnectException",
                "\tat io.acme.Gateway.lambda$call$7(Gateway.java:99)\n\tat io.acme.Gateway$$Lambda/0x9.apply(X)\n"
                        + "\tat io.acme.PayTest.pays(PayTest.java:1)");

        assertThat(functional.facts()).contains(
                "failures: 1 assertion (a check failed), 1 error (the test or its environment threw)",
                "failed: io.acme.PayTest.pays — Connection refused [error] [fingerprint " + fingerprint + "]");
        assertThat(functional.facts()).anyMatch(fact -> fact.startsWith("failed: io.acme.PayTest.refunds — expected: "
                + "200 but was: 500 [fingerprint ") && !fact.contains("[error]"));
        assertThat(fingerprint).hasSize(12)
                .as("line numbers, lambda counters and JDK frames do not count")
                .isEqualTo(JunitResults.fingerprint("java.net.ConnectException",
                        "\tat java.base/java.lang.Thread.run(Thread.java:1)\n"
                                + "\tat io.acme.Gateway.lambda$call$0(Gateway.java:12)\n"
                                + "\tat io.acme.Gateway$$Lambda/0x1.apply(X)\n\tat io.acme.PayTest.pays(PayTest.java:5)"))
                .as("another exception type is another failure")
                .isNotEqualTo(JunitResults.fingerprint("java.net.SocketTimeoutException",
                        "\tat io.acme.Gateway.lambda$call$0(Gateway.java:12)\n"
                                + "\tat io.acme.Gateway$$Lambda/0x1.apply(X)\n\tat io.acme.PayTest.pays(PayTest.java:5)"));
        assertThat(JunitResults.fingerprint(null, "no frames here")).isNull();
    }

    @Test
    void aQuarantinedTestPassesTheGateOnlyWhileItFailsTheSameWay() throws IOException {
        junit(timeout(12));
        String fingerprint = runner(null).run().dimension(QualityDimension.FUNCTIONAL).orElseThrow().facts()
                .stream().filter(f -> f.startsWith("failed: io.acme.PayTest.pays")).findFirst().orElseThrow()
                .replaceAll(".*\\[fingerprint (\\w+)]$", "$1");
        List<String> accepted = List.of(
                "functional io.acme.PayTest.pays @" + fingerprint + "  # QUAR-7, until 2026-11-30",
                "functional io.acme.PayTest.refunds  # ORD-12");

        junit(timeout(20));
        assertThatCode(() -> runner(accepted).assertAcceptable())
                .as("the same failure, moved lines").doesNotThrowAnyException();

        junit(timeout(20).replace("java.net.ConnectException", "java.lang.IllegalStateException"));
        QualityReport report = runner(accepted).run();
        assertThat(report.dimension(QualityDimension.FUNCTIONAL).orElseThrow().newIssues())
                .containsExactly("io.acme.PayTest.pays");
        assertThat(report.notes()).anyMatch(note -> note.startsWith("accepted for fingerprint " + fingerprint
                + ", failed with ") && note.endsWith(" — counted as new: functional io.acme.PayTest.pays"));
        assertThat(report.notes()).noneMatch(note -> note.startsWith("accepted but not seen"));
        assertThatThrownBy(() -> runner(accepted).assertAcceptable())
                .hasMessageContaining("new: io.acme.PayTest.pays");
    }
}
