package io.testforge.api.fuzz;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SchemathesisExecutorTest {

    @Test
    void probesVersionSuccessfully() {
        ProcessRunner fakeRunner = (cmd, wd, env, timeout) ->
                new ProcessResult(0, "st, version 4.24.3\n", "", false);

        SchemathesisExecutor executor = new SchemathesisExecutor(fakeRunner);
        SchemathesisVersion version = executor.probeVersion();

        assertThat(version.major()).isEqualTo(4);
        assertThat(version.minor()).isEqualTo(24);
        assertThat(version.patch()).isEqualTo(3);
        assertThat(version.isSupported()).isTrue();
    }

    @Test
    void rejectsUnsupportedVersion() {
        ProcessRunner fakeRunner = (cmd, wd, env, timeout) ->
                new ProcessResult(0, "st, version 3.9.0\n", "", false);

        SchemathesisExecutor executor = new SchemathesisExecutor(fakeRunner);

        assertThatThrownBy(executor::probeVersion)
                .isInstanceOf(ApiFuzzException.class)
                .hasMessageContaining("Unsupported Schemathesis version")
                .hasMessageContaining("Major version 4 is required");
    }

    @Test
    void missingExecutableThrowsActionableException() {
        ProcessRunner fakeRunner = (cmd, wd, env, timeout) -> {
            throw new ApiFuzzException("Failed to start process", new IOException("Cannot run program"));
        };

        SchemathesisExecutor executor = new SchemathesisExecutor(fakeRunner, "st");

        assertThatThrownBy(executor::probeVersion)
                .isInstanceOf(ApiFuzzException.class)
                .hasMessageContaining("Schemathesis executable 'st' not found")
                .hasMessageContaining("Install it with: uv tool install schemathesis");
    }

    @Test
    void nonZeroExitCodeThrowsActionableException() {
        ProcessRunner fakeRunner = (cmd, wd, env, timeout) ->
                new ProcessResult(127, "", "command not found", false);

        SchemathesisExecutor executor = new SchemathesisExecutor(fakeRunner, "st");

        assertThatThrownBy(executor::probeVersion)
                .isInstanceOf(ApiFuzzException.class)
                .hasMessageContaining("Schemathesis executable 'st' not found")
                .hasMessageContaining("Install it with: uv tool install schemathesis");
    }

    @Test
    void timeoutIsSurfaced() {
        ProcessRunner fakeRunner = (cmd, wd, env, timeout) ->
                new ProcessResult(-1, "", "", true);

        SchemathesisExecutor executor = new SchemathesisExecutor(fakeRunner);

        assertThatThrownBy(executor::probeVersion)
                .isInstanceOf(ApiFuzzException.class)
                .hasMessageContaining("Timeout");
    }

    @Test
    void parsesVersionFromTheSchemathesisExecutableName() {
        // forge.api-fuzz.command may be "schemathesis" rather than "st", and
        // that binary prints a different prefix.
        SchemathesisVersion v = SchemathesisVersion.parse("schemathesis, version 4.24.3");
        assertThat(v.semver()).isEqualTo("4.24.3");
        assertThat(v.isSupported()).isTrue();
    }

    @Test
    void parsesVersionWhenTheBuildAppendsExtraDetail() {
        SchemathesisVersion v = SchemathesisVersion.parse("st, version 4.24.3 (Python 3.13.1)");
        assertThat(v.semver()).isEqualTo("4.24.3");
    }

    @Test
    void blankVersionOutputIsAClearFailureNotANullPointer() {
        assertThatThrownBy(() -> SchemathesisVersion.parse("  "))
                .isInstanceOf(ApiFuzzException.class)
                .hasMessageContaining("no output");
    }
}
