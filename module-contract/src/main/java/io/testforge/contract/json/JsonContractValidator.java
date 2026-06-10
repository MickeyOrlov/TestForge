package io.testforge.contract.json;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.testforge.contract.ContractProperties;
import io.testforge.core.json.JsonPath;
import java.util.ArrayList;
import java.util.List;

public class JsonContractValidator {

    private final ObjectMapper objectMapper;
    private final ContractProperties properties;

    public JsonContractValidator(ObjectMapper objectMapper, ContractProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public List<ContractViolation> validate(String json, MessageContract contract) {
        try {
            return validate(objectMapper.readTree(json), contract);
        } catch (JsonProcessingException e) {
            return List.of(new ContractViolation(
                    contract.name(),
                    "$",
                    "invalid-json",
                    "Payload is not valid JSON: " + e.getOriginalMessage()));
        }
    }

    public List<ContractViolation> validate(JsonNode root, MessageContract contract) {
        List<ContractViolation> violations = new ArrayList<>();

        for (FieldRule rule : contract.rules()) {
            JsonNode value = JsonPath.read(root, rule.path());

            if (value.isMissingNode()) {
                if (rule.required()) {
                    add(violations, contract, rule.path(), "missing", "Required field is missing");
                }
                if (shouldStop(violations)) {
                    break;
                }
                continue;
            }

            if (value.isNull()) {
                if (!rule.nullable()) {
                    add(violations, contract, rule.path(), "null", "Field must not be null");
                }
                if (shouldStop(violations)) {
                    break;
                }
                continue;
            }

            if (!rule.type().matches(value)) {
                add(violations, contract, rule.path(), "type",
                        "Expected " + rule.type() + " but got " + describe(value));
            }
            if (shouldStop(violations)) {
                break;
            }
        }

        return List.copyOf(violations);
    }

    public void assertValid(String json, MessageContract contract) {
        List<ContractViolation> violations = validate(json, contract);
        if (!violations.isEmpty()) {
            throw new ContractValidationException(violations);
        }
    }

    private void add(List<ContractViolation> violations, MessageContract contract,
                     String path, String code, String message) {
        violations.add(new ContractViolation(contract.name(), path, code, message));
    }

    private boolean shouldStop(List<ContractViolation> violations) {
        return !violations.isEmpty()
                && (properties.failFast() || violations.size() >= properties.maxViolations());
    }

    private String describe(JsonNode node) {
        if (node.isTextual()) {
            return "STRING";
        }
        if (node.isIntegralNumber()) {
            return "INTEGER";
        }
        if (node.isNumber()) {
            return "NUMBER";
        }
        if (node.isBoolean()) {
            return "BOOLEAN";
        }
        if (node.isObject()) {
            return "OBJECT";
        }
        if (node.isArray()) {
            return "ARRAY";
        }
        return node.getNodeType().name();
    }
}
