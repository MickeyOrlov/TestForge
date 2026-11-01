package io.testforge.security;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;

/** Scanner seam; raw evidence is ephemeral input, never application diagnostics. */
public interface ZapEngine {
  Result execute(Path privateDirectory, SecurityActiveProperties properties);

  enum Phase {
    READY,
    STARTUP,
    INPUT,
    EXECUTION,
    TIMEOUT,
    REPORT,
    CLEANUP
  }

  record Result(boolean complete, JsonNode report, Phase phase, int exitCode) {
    public Result(boolean complete, JsonNode report) {
      this(complete, report, complete ? Phase.READY : Phase.EXECUTION, -1);
    }

    @Override
    public String toString() {
      return "ZapEngine.Result[phase=" + phase + ", exit=" + exitCode + ", raw evidence omitted]";
    }
  }
}
