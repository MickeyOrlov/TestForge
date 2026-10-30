package io.testforge.api.explorer;

import java.util.List;

/**
 * What became of one extraction rule in a run. The extracted value itself is
 * never recorded here: the report says whether the chain worked, the
 * observation of the consuming call shows where the value went.
 */
public record ExtractionOutcome(String from, String jsonPath, List<String> into, Status status, String detail) {

    public enum Status {
        EXTRACTED("value extracted"),
        PRODUCER_NOT_RUN("the producing operation was skipped or is not selected"),
        PRODUCER_FAILED("the producing call failed or did not answer 2xx"),
        NOT_JSON("the producing response is not JSON"),
        NOT_FOUND("json-path matched nothing"),
        NOT_A_VALUE("json-path matched an object or array, not a single value");

        private final String description;

        Status(String description) {
            this.description = description;
        }

        public String description() {
            return description;
        }
    }

    public ExtractionOutcome {
        into = List.copyOf(into == null ? List.of() : into);
    }

    static ExtractionOutcome of(ExtractionRule rule, Status status, String detail) {
        return new ExtractionOutcome(rule.from(), rule.jsonPath(), rule.into(), status, detail);
    }
}
