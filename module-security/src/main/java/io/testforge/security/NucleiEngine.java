package io.testforge.security;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.file.Path;
import java.util.List;

/** Delegates the fixed policy to Nuclei. Raw JSONL is internal and ephemeral. */
public interface NucleiEngine {
    Result execute(Path directory, List<String> targets, SecurityNucleiProperties properties);

    enum Phase {
        COMPLETE,
        EXECUTION,
        TIMEOUT,
        REPORT,
        CLEANUP
    }

    record Result(Phase phase, JsonNode matches) {
        @Override
        public String toString() {
            return "NucleiEngine.Result[phase=" + phase + "]";
        }
    }
}
