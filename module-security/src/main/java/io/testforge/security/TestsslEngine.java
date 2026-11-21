package io.testforge.security;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.file.Path;

/** testssl.sh seam; its raw JSON is ephemeral input, never a report or diagnostic. */
public interface TestsslEngine {
    /**
     * Assesses {@code hostPort} with the fixed TestForge policy and returns testssl.sh's flat JSON
     * findings, or a result without a report when the run did not complete.
     */
    Result execute(Path privateDirectory, String hostPort, SecurityTransportProperties.Testssl p);

    enum Phase {
        READY,
        EXECUTION,
        TIMEOUT,
        REPORT,
        CLEANUP
    }

    record Result(JsonNode report, Phase phase, int exitCode) {
        @Override
        public String toString() {
            return "TestsslEngine.Result[phase=" + phase + ", exit=" + exitCode
                    + ", raw evidence omitted]";
        }
    }
}
