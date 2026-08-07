package io.testforge.api.explorer;

import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Turns an operation into a request, or into a reason it cannot become one.
 *
 * <p>Two rules shape v1.
 *
 * <p><b>Nothing is sent that the document did not ask for.</b> Optional query
 * parameters are left out unless a human configured them. Filling in an
 * invented {@code ?status=testforge} would change what the endpoint returns,
 * and the run is supposed to record what the endpoint returns by default.
 *
 * <p><b>Request bodies are not invented.</b> An operation that requires one is
 * skipped with a reason. Generating a body means guessing at business meaning,
 * and a wrong guess against a write endpoint is exactly the damage the safety
 * policy exists to prevent. Bodies arrive when there is a real source for them
 * — a configured payload, or a value produced by an earlier response.
 */
public class RequestPlanner {

    private final RequestValueResolver values;

    public RequestPlanner(RequestValueResolver values) {
        this.values = values;
    }

    public PlannedRequest plan(ExplorableOperation operation) {
        List<ParameterBinding> bindings = new ArrayList<>();

        RequestBody body = operation.operation().getRequestBody();
        if (body != null && Boolean.TRUE.equals(body.getRequired())) {
            return PlannedRequest.skip(SkipReason.REQUEST_BODY_REQUIRED,
                    "v1 does not synthesize request bodies", bindings);
        }

        Map<String, String> pathParameters = new LinkedHashMap<>();
        Map<String, String> queryParameters = new LinkedHashMap<>();
        List<String> missingPath = new ArrayList<>();
        List<String> missingQuery = new ArrayList<>();

        for (Parameter parameter : operation.parameters()) {
            String in = parameter.getIn();
            boolean pathParameter = "path".equals(in);
            boolean requiredQuery = "query".equals(in) && Boolean.TRUE.equals(parameter.getRequired());

            if (!pathParameter && !requiredQuery && !"query".equals(in)) {
                // header and cookie parameters belong to the environment, not to
                // the explorer: ApiClient's customizers already own them
                continue;
            }

            Optional<ParameterBinding> resolved = values.resolve(operation, parameter);
            if (resolved.isEmpty()) {
                if (pathParameter) {
                    missingPath.add(parameter.getName());
                } else if (requiredQuery) {
                    missingQuery.add(parameter.getName());
                }
                continue;
            }

            ParameterBinding binding = resolved.get();
            if (pathParameter) {
                bindings.add(binding);
                pathParameters.put(binding.name(), binding.value());
            } else if (requiredQuery || binding.source() == ValueSource.CONFIGURED) {
                bindings.add(binding);
                queryParameters.put(binding.name(), binding.value());
            }
        }

        if (!missingPath.isEmpty()) {
            return PlannedRequest.skip(SkipReason.MISSING_PATH_PARAMETER,
                    String.join(", ", missingPath), bindings);
        }
        if (!missingQuery.isEmpty()) {
            return PlannedRequest.skip(SkipReason.MISSING_REQUIRED_QUERY_PARAMETER,
                    String.join(", ", missingQuery), bindings);
        }

        return PlannedRequest.of(
                new PreparedRequest(operation.method(), operation.pathTemplate(), pathParameters, queryParameters),
                bindings);
    }
}
