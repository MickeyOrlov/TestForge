package io.testforge.contract.json;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Strict parser preset for contract validation. A validator that silently
 * "repairs" broken JSON hides exactly the drift it exists to catch:
 *
 * <ul>
 *   <li>{@code STRICT_DUPLICATE_DETECTION} — a duplicate key is a producer
 *       bug; default parsers silently keep the last value;</li>
 *   <li>{@code FAIL_ON_TRAILING_TOKENS} — garbage after the document is a
 *       framing/concatenation bug, not a valid payload.</li>
 * </ul>
 */
public final class ContractMappers {

    private ContractMappers() {
    }

    public static ObjectMapper strict() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        mapper.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        return mapper;
    }
}
