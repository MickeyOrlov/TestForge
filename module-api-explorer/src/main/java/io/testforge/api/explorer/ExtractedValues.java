package io.testforge.api.explorer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.testforge.core.json.JsonPath;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Values extracted during one spec's exploration, and the fate of every rule.
 * Lives exactly as long as one spec run: nothing leaks between specs or runs.
 */
public final class ExtractedValues {

    /** For callers that do not explore statefully. */
    public static final ExtractedValues NONE = new ExtractedValues(List.of(), new ObjectMapper());

    private final List<ExtractionRule> rules;
    private final ObjectMapper mapper;
    private final Map<ExtractionRule, String> values = new LinkedHashMap<>();
    private final Map<ExtractionRule, ExtractionOutcome> outcomes = new LinkedHashMap<>();

    ExtractedValues(List<ExtractionRule> rules, ObjectMapper mapper) {
        this.rules = List.copyOf(rules);
        this.mapper = mapper;
    }

    /** The extracted value for this parameter, if a rule feeds it and has produced one. */
    Optional<String> find(String operationId, String parameter) {
        for (ExtractionRule rule : rules) {
            if (rule.feeds(operationId, parameter) && values.containsKey(rule)) {
                return Optional.of(values.get(rule));
            }
        }
        return Optional.empty();
    }

    /** Reads every rule produced by {@code operationId} from its raw, unredacted response. */
    void record(String operationId, RuntimeExchange exchange) {
        for (ExtractionRule rule : rules) {
            if (!rule.from().equals(operationId) || values.containsKey(rule)) {
                continue;
            }
            if (exchange == null || !exchange.completed() || exchange.status() / 100 != 2) {
                outcomes.put(rule, ExtractionOutcome.of(rule, ExtractionOutcome.Status.PRODUCER_FAILED,
                        exchange == null || !exchange.completed() ? "no response" : "status " + exchange.status()));
                continue;
            }
            JsonNode root;
            try {
                root = exchange.responseBody() == null ? null : mapper.readTree(exchange.responseBody());
            } catch (Exception e) {
                root = null;
            }
            if (root == null) {
                outcomes.put(rule, ExtractionOutcome.of(rule, ExtractionOutcome.Status.NOT_JSON, exchange.contentType()));
                continue;
            }
            JsonNode value = JsonPath.read(root, rule.jsonPath());
            if (value.isMissingNode() || value.isNull()) {
                outcomes.put(rule, ExtractionOutcome.of(rule, ExtractionOutcome.Status.NOT_FOUND, null));
            } else if (!value.isValueNode()) {
                outcomes.put(rule, ExtractionOutcome.of(rule, ExtractionOutcome.Status.NOT_A_VALUE,
                        value.getNodeType().name()));
            } else {
                values.put(rule, value.asText());
                outcomes.put(rule, ExtractionOutcome.of(rule, ExtractionOutcome.Status.EXTRACTED, null));
            }
        }
    }

    /** One outcome per rule, in configuration order; rules never reached say so. */
    List<ExtractionOutcome> outcomes() {
        List<ExtractionOutcome> all = new ArrayList<>();
        for (ExtractionRule rule : rules) {
            all.add(outcomes.getOrDefault(rule,
                    ExtractionOutcome.of(rule, ExtractionOutcome.Status.PRODUCER_NOT_RUN, null)));
        }
        return all;
    }
}
