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
    private final Map<String, String> bodies;

    public RequestPlanner(RequestValueResolver values) {
        this(values, Map.of());
    }

    /**
     * @param bodies request body templates per operationId
     *               ({@code forge.api-explorer.bodies}); {@code {{unique}}} becomes a
     *               fresh token per request, so repeated runs never collide
     */
    public RequestPlanner(RequestValueResolver values, Map<String, String> bodies) {
        this.values = values;
        this.bodies = Map.copyOf(bodies);
    }

    public PlannedRequest plan(ExplorableOperation operation) {
        return plan(operation, ExtractedValues.NONE);
    }

    public PlannedRequest plan(ExplorableOperation operation, ExtractedValues extracted) {
        List<ParameterBinding> bindings = new ArrayList<>();

        RequestBody body = operation.operation().getRequestBody();
        String template = bodies.get(operation.operationId());
        if (template == null && body != null && Boolean.TRUE.equals(body.getRequired())) {
            // bodies are never synthesized: a guessed payload against a write endpoint
            // is exactly what the safety policy exists to prevent
            return PlannedRequest.skip(SkipReason.REQUEST_BODY_REQUIRED,
                    "configure forge.api-explorer.bodies." + operation.operationId(), bindings);
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

            Optional<ParameterBinding> resolved = values.resolve(operation, parameter, extracted);
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
            } else if (requiredQuery || binding.source() == ValueSource.CONFIGURED
                    || binding.source() == ValueSource.EXTRACTED) {
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
                new PreparedRequest(operation.method(), operation.pathTemplate(), pathParameters, queryParameters,
                        template == null ? null : render(template), template == null ? null : contentType(body)),
                bindings);
    }

    private static String render(String template) {
        String result = template;
        while (result.contains("{{unique}}")) {
            result = result.replaceFirst(java.util.regex.Pattern.quote("{{unique}}"),
                    "tf-" + java.util.UUID.randomUUID().toString().substring(0, 8));
        }
        return result;
    }

    /** The document's first JSON media type, else {@code application/json}. */
    private static String contentType(RequestBody body) {
        if (body != null && body.getContent() != null) {
            for (String media : body.getContent().keySet()) {
                if (media.toLowerCase(java.util.Locale.ROOT).contains("json")) {
                    return media;
                }
            }
        }
        return "application/json";
    }
}
