package io.testforge.api.fuzz;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Optional;

/**
 * Applies exactly one case to the baseline body.
 *
 * <p>The baseline is copied first, so every case starts from the same valid
 * document and no case can see what another did. Without that, the fifth case
 * of an operation would be testing the wreckage of the first four.
 */
public class JsonBodyMutator {

    private final ObjectMapper objectMapper;

    public JsonBodyMutator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** The mutated body as JSON text, or empty when the case does not apply to this baseline. */
    public Optional<String> apply(JsonNode baseline, FuzzCase fuzzCase) {
        JsonNode mutated = baseline.deepCopy();
        String path = fuzzCase.parameterName();

        boolean applied = switch (fuzzCase.kind()) {
            case OMITTED_REQUIRED -> BodyPaths.remove(mutated, path);
            case NULL_FOR_NON_NULLABLE -> BodyPaths.set(mutated, path, objectMapper.getNodeFactory().nullNode());
            case EMPTY_ARRAY, TOO_FEW_ITEMS, TOO_MANY_ITEMS -> resize(mutated, fuzzCase);
            case AT_LOWER_BOUND, AT_UPPER_BOUND -> boundary(mutated, fuzzCase);
            case INVALID_ITEM_TYPE -> replaceFirstElement(mutated, path);
            case DUPLICATE_ITEM -> duplicateFirstElement(mutated, path);
            case UNDECLARED_PROPERTY -> addUndeclaredProperty(mutated, path, fuzzCase.value());
            default -> BodyPaths.set(mutated, path, parse(fuzzCase.value()));
        };

        return applied ? Optional.of(mutated.toString()) : Optional.empty();
    }

    /** A boundary case is a resize for arrays and a plain value everywhere else. */
    private boolean boundary(JsonNode mutated, FuzzCase fuzzCase) {
        JsonNode target = BodyPaths.resolve(mutated, fuzzCase.parameterName()).orElse(null);
        if (target != null && target.isArray()) {
            return resize(mutated, fuzzCase);
        }
        return BodyPaths.set(mutated, fuzzCase.parameterName(), parse(fuzzCase.value()));
    }

    private boolean resize(JsonNode mutated, FuzzCase fuzzCase) {
        JsonNode target = BodyPaths.resolve(mutated, fuzzCase.parameterName()).orElse(null);
        if (!(target instanceof ArrayNode array)) {
            return false;
        }

        int size;
        try {
            size = Integer.parseInt(fuzzCase.value());
        } catch (NumberFormatException e) {
            return false;
        }

        JsonNode element = array.isEmpty()
                ? objectMapper.getNodeFactory().textNode("testforge")
                : array.get(0).deepCopy();
        array.removeAll();
        for (int index = 0; index < size; index++) {
            array.add(element.deepCopy());
        }
        return true;
    }

    /**
     * Swaps the first element for one of a type the item schema does not
     * declare — an object where strings were promised, a string where objects
     * were.
     */
    private boolean replaceFirstElement(JsonNode mutated, String arrayPath) {
        JsonNode target = BodyPaths.resolve(mutated, arrayPath).orElse(null);
        if (!(target instanceof ArrayNode array) || array.isEmpty()) {
            return false;
        }

        JsonNode replacement = array.get(0).isTextual()
                ? objectMapper.createObjectNode().put("testforge", true)
                : objectMapper.getNodeFactory().textNode("testforge");
        array.set(0, replacement);
        return true;
    }

    /**
     * Repeats an element rather than appending a new one where the array
     * already has room, so the mutant differs from the baseline in exactly one
     * respect — uniqueness — and not in length as well.
     */
    private boolean duplicateFirstElement(JsonNode mutated, String arrayPath) {
        JsonNode target = BodyPaths.resolve(mutated, arrayPath).orElse(null);
        if (!(target instanceof ArrayNode array) || array.isEmpty()) {
            return false;
        }
        if (array.size() >= 2) {
            array.set(1, array.get(0).deepCopy());
        } else {
            array.add(array.get(0).deepCopy());
        }
        return true;
    }

    /** Adds a property the schema never declared, next to everything it did. */
    private boolean addUndeclaredProperty(JsonNode mutated, String objectPath, String name) {
        JsonNode target = BodyPaths.resolve(mutated, objectPath).orElse(null);
        if (!(target instanceof ObjectNode object)) {
            return false;
        }
        object.put(name, "testforge");
        return true;
    }

    private JsonNode parse(String value) {
        if (value == null) {
            return objectMapper.getNodeFactory().nullNode();
        }
        try {
            return objectMapper.readTree(value);
        } catch (Exception e) {
            return objectMapper.getNodeFactory().textNode(value);
        }
    }
}
