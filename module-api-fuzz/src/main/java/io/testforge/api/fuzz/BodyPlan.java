package io.testforge.api.fuzz;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.RequestBody;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * What this module can do with one operation's request body.
 *
 * <p>Three outcomes, and the difference between them matters. An operation with
 * no body is fuzzed on its parameters alone. An operation whose body could be
 * built gets body cases as well, and its parameter cases carry that valid body
 * so they still test what they claim to. An operation whose body could
 * <em>not</em> be built is skipped with the reason — never sent a plausible
 * guess, because a request the schema would have rejected anyway makes every
 * verdict meaningless.
 */
public record BodyPlan(
        boolean declared,
        boolean required,
        String contentType,
        Schema<?> schema,
        JsonNode baseline,
        Set<String> unfuzzablePaths,
        String unsupportedReason) {

    private static final String JSON = "application/json";

    public BodyPlan {
        unfuzzablePaths = Set.copyOf(unfuzzablePaths == null ? Set.of() : unfuzzablePaths);
    }

    public static BodyPlan none() {
        return new BodyPlan(false, false, null, null, null, Set.of(), null);
    }

    public boolean usable() {
        return baseline != null;
    }

    /** True when the operation cannot be fuzzed at all without a body nobody could build. */
    public boolean blocked() {
        return required && !usable();
    }

    /**
     * Reads the operation's {@code application/json} request body, or reports
     * why there is nothing to work with. Media types other than JSON are out of
     * scope in this increment and say so rather than being guessed at.
     */
    public static BodyPlan from(Operation operation, JsonBodyFactory factory) {
        RequestBody body = operation.getRequestBody();
        if (body == null || body.getContent() == null || body.getContent().isEmpty()) {
            return none();
        }

        boolean required = Boolean.TRUE.equals(body.getRequired());
        Map.Entry<String, MediaType> json = body.getContent().entrySet().stream()
                .filter(entry -> entry.getKey().toLowerCase(Locale.ROOT).contains("json"))
                .findFirst()
                .orElse(null);

        if (json == null) {
            return new BodyPlan(true, required, null, null, null, Set.of(),
                    "request body media types " + body.getContent().keySet() + " are not supported; JSON only");
        }

        Schema<?> schema = json.getValue().getSchema();
        JsonBodyFactory.Baseline baseline = factory.build(schema);
        if (!baseline.usable()) {
            return new BodyPlan(true, required, json.getKey(), schema, null, Set.of(),
                    baseline.unsupportedReason());
        }
        return new BodyPlan(true, required, json.getKey(), schema, baseline.body(),
                baseline.unfuzzablePaths(), null);
    }
}
