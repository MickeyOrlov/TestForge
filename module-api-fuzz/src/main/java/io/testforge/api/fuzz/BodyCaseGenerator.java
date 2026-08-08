package io.testforge.api.fuzz;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.models.media.Schema;
import io.testforge.api.explorer.ExplorableOperation;
import io.testforge.api.explorer.Schemas;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Walks a request-body schema and produces one case per field per mutation.
 *
 * <p>The constraint reasoning is not repeated here — it comes from
 * {@link SchemaMutations}, the same source the parameter cases use. This class
 * only decides <em>where</em> in the body each mutation applies and what the
 * path is called.
 *
 * <p>One field per case, always. A body with an invalid name and an invalid age
 * tells you the request was rejected; it does not tell you which field the
 * service objected to, and combinations are explicitly not in this increment.
 */
public class BodyCaseGenerator {

    private static final int MAX_DEPTH = 8;

    /** Distinctive enough that a service echoing it back is unmistakable. */
    static final String UNDECLARED_PROPERTY = "testforgeUndeclared";

    private final ObjectMapper objectMapper;
    private final JsonBodyFactory bodyFactory;
    private final Compositions compositions;

    public BodyCaseGenerator(ObjectMapper objectMapper, JsonBodyFactory bodyFactory) {
        this.objectMapper = objectMapper;
        this.bodyFactory = bodyFactory;
        this.compositions = new Compositions(bodyFactory::effective);
    }

    public List<FuzzCase> generate(ExplorableOperation operation, Schema<?> bodySchema,
                                   Set<String> unfuzzablePaths) {
        List<FuzzCase> cases = new ArrayList<>();
        walk(operation, bodyFactory.effective(bodySchema), "$", cases, unfuzzablePaths, 0);
        return List.copyOf(cases);
    }

    private void walk(ExplorableOperation operation, Schema<?> schema, String path,
                      List<FuzzCase> cases, Set<String> unfuzzable, int depth) {

        if (schema == null || depth > MAX_DEPTH || underUnfuzzable(path, unfuzzable)) {
            return;
        }

        if (Compositions.branching(schema)) {
            // reached here only when the baseline proved the branch is pinned —
            // an unpinned one is in unfuzzablePaths and was turned away above.
            // Descending into the branch is what makes a discriminated union
            // fuzzable at all; treating the composition itself as a value would
            // produce string cases against an object
            walk(operation, bodyFactory.effective(compositions.choose(schema).branch()),
                    path, cases, unfuzzable, depth);
            return;
        }

        String type = Schemas.type(schema);
        if (schema.getProperties() != null && !schema.getProperties().isEmpty()) {
            walkObject(operation, schema, path, cases, unfuzzable, depth);
            return;
        }
        if ("array".equals(type)) {
            addMutations(operation, schema, path, cases);
            Schema<?> items = bodyFactory.effective(schema.getItems());
            if (items != null) {
                // the first element stands for all of them: one mutation, one
                // place, one line in the report
                walk(operation, items, BodyPaths.element(path, 0), cases, unfuzzable, depth + 1);
            }
            return;
        }
        addMutations(operation, schema, path, cases);
    }

    private void walkObject(ExplorableOperation operation, Schema<?> schema, String path,
                            List<FuzzCase> cases, Set<String> unfuzzable, int depth) {

        List<String> required = schema.getRequired() == null ? List.of() : schema.getRequired();

        // an undeclared property is only forbidden where the document says so.
        // Absent additionalProperties permits extras outright, and a
        // schema-valued one constrains rather than bans them, so neither earns
        // a REJECT — sending one there would accuse a service of allowing what
        // its own document allows
        if (SchemaFacts.additionalPropertiesForbidden(schema)) {
            cases.add(FuzzCase.body(operation.specId(), operation.operationId(), operation.key(),
                    path, FuzzCaseKind.UNDECLARED_PROPERTY, FuzzExpectation.REJECT, "additionalProperties",
                    UNDECLARED_PROPERTY));
        }

        for (Map.Entry<String, Schema> property : schema.getProperties().entrySet()) {
            String childPath = BodyPaths.child(path, property.getKey());
            if (underUnfuzzable(childPath, unfuzzable)) {
                continue;
            }

            if (SchemaFacts.readOnly(property.getValue())) {
                // the baseline leaves these out, so the mutation is to put one
                // back. OpenAPI says a request SHOULD NOT carry them, which is
                // guidance — a service that quietly accepts and ignores it is
                // not breaking a promise, and a crash still is
                readOnlyCase(operation, property.getValue(), childPath).ifPresent(cases::add);
                continue;
            }

            // only a declared required field proves anything by its absence;
            // dropping an optional one breaks no promise
            if (required.contains(property.getKey())) {
                cases.add(FuzzCase.omitting(operation.specId(), operation.operationId(), operation.key(),
                        childPath, FuzzCase.BODY));
            }
            walk(operation, bodyFactory.effective(property.getValue()), childPath, cases, unfuzzable, depth + 1);
        }
    }

    /** Reuses the baseline factory, so the value put back is one the document would accept. */
    private Optional<FuzzCase> readOnlyCase(ExplorableOperation operation, Schema<?> schema, String path) {
        JsonBodyFactory.Baseline value = bodyFactory.build(schema);
        if (!value.usable()) {
            return Optional.empty();
        }
        return Optional.of(FuzzCase.body(operation.specId(), operation.operationId(), operation.key(),
                path, FuzzCaseKind.READ_ONLY_IN_REQUEST, FuzzExpectation.UNSPECIFIED, "readOnly",
                value.body().toString()));
    }

    private void addMutations(ExplorableOperation operation, Schema<?> schema, String path, List<FuzzCase> cases) {
        for (SchemaMutations.Mutation mutation : SchemaMutations.forSchema(schema, true)) {
            cases.add(FuzzCase.body(operation.specId(), operation.operationId(), operation.key(),
                    path, mutation.kind(), mutation.expectation(), mutation.constraint(), render(mutation)));
        }
    }

    /**
     * Body values are rendered as JSON text, so a case records the difference
     * between the string {@code "17"} and the number {@code 17} — which is
     * exactly what a {@code WRONG_TYPE} case is about. Array size mutations
     * record the target size instead; the mutator resizes the baseline array.
     */
    private String render(SchemaMutations.Mutation mutation) {
        Object value = mutation.value();
        return switch (mutation.kind()) {
            case EMPTY_ARRAY, TOO_FEW_ITEMS, TOO_MANY_ITEMS, AT_LOWER_BOUND, AT_UPPER_BOUND -> arrayOrValue(value);
            case INVALID_ITEM_TYPE -> "<invalid item type>";
            case DUPLICATE_ITEM -> "<duplicate of the first element>";
            default -> json(value);
        };
    }

    private String arrayOrValue(Object value) {
        return value instanceof Integer size ? String.valueOf(size) : json(value);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    private boolean underUnfuzzable(String path, Set<String> unfuzzable) {
        return unfuzzable.stream().anyMatch(prefix ->
                path.equals(prefix) || path.startsWith(prefix + ".") || path.startsWith(prefix + "["));
    }
}
