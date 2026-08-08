package io.testforge.api.fuzz;

import io.swagger.v3.oas.models.parameters.Parameter;
import io.testforge.api.explorer.ExplorableOperation;
import java.util.ArrayList;
import java.util.List;

/**
 * Cases for path and query parameters.
 *
 * <p>The constraint reasoning lives in {@link SchemaMutations}, shared with the
 * request-body generator: {@code ?age=0} and {@code $.profile.age = 0} are the
 * same question asked in two places, and they must get the same answer.
 */
public class FuzzCaseGenerator {

    public List<FuzzCase> generate(ExplorableOperation operation) {
        List<FuzzCase> cases = new ArrayList<>();
        for (Parameter parameter : operation.parameters()) {
            String in = parameter.getIn();
            if (!"path".equals(in) && !"query".equals(in)) {
                // header and cookie parameters belong to the environment; the
                // explorer does not set them and neither does this
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

        for (SchemaMutations.Mutation mutation : SchemaMutations.forSchema(parameter.getSchema(), false)) {
            if (mutation.value() == null) {
                // nothing in a URL can express a JSON null
                continue;
            }
            cases.add(FuzzCase.parameter(operation.specId(), operation.operationId(), operation.key(),
                    parameter.getName(), parameter.getIn(), mutation.kind(), mutation.expectation(),
                    String.valueOf(mutation.value())));
        }
        return cases;
    }
}
