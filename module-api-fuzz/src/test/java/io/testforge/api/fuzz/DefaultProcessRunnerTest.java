package io.testforge.api.fuzz;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * These use ordinary shell utilities rather than Schemathesis: the behaviour
 * under test belongs to the process seam, not to the fuzzer.
 */
@DisabledOnOs(OS.WINDOWS)
class DefaultProcessRunnerTest {

    private final DefaultProcessRunner runner = new DefaultProcessRunner();

    @Test
    void capturesOutputLargerThanThePipeBuffer() {
        // The regression this guards: reading stdout only after waitFor() lets a
        // child fill the ~64KB pipe buffer, block on write, and never exit — so a
        // merely verbose run was reported as a timeout. Schemathesis is verbose,
        // so this is the normal path.
        ProcessResult result = runner.run(
                List.of("sh", "-c", "yes ABCDEFGHIJ | head -c 2000000"),
                Path.of("."), Map.of(), Duration.ofSeconds(30));

        assertThat(result.timedOut()).isFalse();
        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout().length()).isGreaterThan(1_000_000);
    }

    @Test
    void capturesLargeStderrWithoutStalling() {
        ProcessResult result = runner.run(
                List.of("sh", "-c", "yes ERRORLINE | head -c 500000 1>&2"),
                Path.of("."), Map.of(), Duration.ofSeconds(30));

        assertThat(result.timedOut()).isFalse();
        assertThat(result.stderr().length()).isGreaterThan(200_000);
    }

    @Test
    void reportsTimeoutAndKeepsWhateverWasPrinted() {
        ProcessResult result = runner.run(
                List.of("sh", "-c", "echo before-hang; sleep 30"),
                Path.of("."), Map.of(), Duration.ofSeconds(2));

        assertThat(result.timedOut()).isTrue();
        // The tail of a timed-out run is usually the only clue about where it stuck.
        assertThat(result.stdout()).contains("before-hang");
    }

    @Test
    void separatesStdoutFromStderr() {
        ProcessResult result = runner.run(
                List.of("sh", "-c", "echo out; echo err 1>&2; exit 3"),
                Path.of("."), Map.of(), Duration.ofSeconds(30));

        assertThat(result.exitCode()).isEqualTo(3);
        assertThat(result.stdout()).contains("out").doesNotContain("err");
        assertThat(result.stderr()).contains("err").doesNotContain("out");
    }
}
