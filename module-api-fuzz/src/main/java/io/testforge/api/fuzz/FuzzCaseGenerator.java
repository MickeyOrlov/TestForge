package io.testforge.api.fuzz;

import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.testforge.api.explorer.ExplorableOperation;
import io.testforge.api.explorer.Schemas;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Cases for path and query parameters.
 *
 * <p>The constraint reasoning lives in {@link SchemaMutations}, shared with the
 * request-body generator: {@code ?age=0} and {@code $.profile.age = 0} are the
 * same question asked in two places, and they must get the same answer.
 *
 * <p>What is different about a parameter is the wire. A body field carries its
 * own type; a parameter is text, and an array one is text assembled according to
 * a declared {@code style}. So every array case here is rendered through
 * {@link ParameterSerialization}, and a parameter whose style has no
 * single-valued form produces no cases at all rather than a comma-joined
 * approximation the document never described.
 */
public class FuzzCaseGenerator {

    private final ConstraintAwareValueFactory values = new ConstraintAwareValueFactory();

    public List<FuzzCase> generate(ExplorableOperation operation) {
        List<FuzzCase> cases = new ArrayList<>();
        for (Parameter parameter : operation.parameters()) {
            String in = parameter.getIn();
            if (!"path".equals(in) && !"query".equals(in)) {
                // header and cookie parameters belong to the environment; the
                // explorer does not set them and neither does this
                continue;
            }
            if (ParameterSerialization.unsupported(parameter).isPresent()) {
                // reported through the coverage model instead, with the reason
                continue;
            }
            cases.addAll(forParameter(operation, parameter));
        }
        return List.copyOf(cases);
    }

    private List<FuzzCase> forParameter(ExplorableOperation operation, Parameter parameter) {
        List<FuzzCase> cases = new ArrayList<>();

        if (Boolean.TRUE.equals(parameter.getRequired()) && "query".equals(parameter.getIn())) {
            // omitting a path parameter addresses a different endpoint, so only
            // a query parameter can be dropped and still mean what it looks like
            cases.add(FuzzCase.omitting(operation.specId(), operation.operationId(), operation.key(),
                    parameter.getName(), parameter.getIn()));
        }

        if ("array".equals(Schemas.type(parameter.getSchema()))) {
            cases.addAll(forArray(operation, parameter));
            return cases;
        }

        for (SchemaMutations.Mutation mutation : SchemaMutations.forSchema(parameter.getSchema(), false)) {
            if (mutation.value() == null) {
                // nothing in a URL can express a JSON null
                continue;
            }
            cases.add(FuzzCase.parameter(operation.specId(), operation.operationId(), operation.key(),
                    parameter.getName(), parameter.getIn(), mutation.kind(), mutation.expectation(),
                    mutation.constraint(), String.valueOf(mutation.value())));
        }
        return cases;
    }

    /**
     * Two layers for an array parameter: the size constraints of the array
     * itself, and the constraints of one element, mutated in place so the rest
     * of the array stays valid.
     */
    private List<FuzzCase> forArray(ExplorableOperation operation, Parameter parameter) {
        List<FuzzCase> cases = new ArrayList<>();
        Schema<?> schema = parameter.getSchema();

        for (SchemaMutations.Mutation mutation : SchemaMutations.forSchema(schema, true)) {
            // in a URL every element is text, so no element can be given a type
            // the item schema does not declare; a null cannot be expressed either
            if (mutation.kind() == FuzzCaseKind.INVALID_ITEM_TYPE
                    || mutation.kind() == FuzzCaseKind.NULL_FOR_NON_NULLABLE) {
                continue;
            }
            serializedArray(parameter, schema, mutation).ifPresent(value ->
                    cases.add(FuzzCase.parameter(operation.specId(), operation.operationId(), operation.key(),
                            parameter.getName(), parameter.getIn(), mutation.kind(), mutation.expectation(),
                            mutation.constraint(), value)));
        }

        int size = baselineSize(schema);
        Optional<List<String>> baseline = ParameterSerialization.elements(schema, size, values);
        if (baseline.isEmpty()) {
            return cases;
        }
        for (SchemaMutations.Mutation mutation : SchemaMutations.forSchema(schema.getItems(), false)) {
            if (mutation.value() == null) {
                continue;
            }
            List<String> mutated = new ArrayList<>(baseline.get());
            mutated.set(0, String.valueOf(mutation.value()));
            cases.add(FuzzCase.arrayItem(operation.specId(), operation.operationId(), operation.key(),
                    parameter.getName(), parameter.getIn(), mutation.kind(), mutation.expectation(),
                    mutation.constraint(), ParameterSerialization.join(parameter, mutated)));
        }
        return cases;
    }

    /** An array mutation names a size, or a repeat; both have to become text. */
    private Optional<String> serializedArray(Parameter parameter, Schema<?> schema,
                                             SchemaMutations.Mutation mutation) {
        if (mutation.kind() == FuzzCaseKind.DUPLICATE_ITEM) {
            return ParameterSerialization.elements(schema, 1, values)
                    .map(elements -> ParameterSerialization.join(parameter,
                            List.of(elements.getFirst(), elements.getFirst())));
        }
        if (!(mutation.value() instanceof Integer size)) {
            return Optional.empty();
        }
        return ParameterSerialization.elements(schema, size, values)
                .map(elements -> ParameterSerialization.join(parameter, elements));
    }

    private int baselineSize(Schema<?> schema) {
        int size = Math.max(schema.getMinItems() == null ? 1 : schema.getMinItems(), 1);
        return schema.getMaxItems() == null ? size : Math.min(size, schema.getMaxItems());
    }
}
