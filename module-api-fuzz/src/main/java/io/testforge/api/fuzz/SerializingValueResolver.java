package io.testforge.api.fuzz;

import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.testforge.api.explorer.ApiExplorerProperties;
import io.testforge.api.explorer.ExplorableOperation;
import io.testforge.api.explorer.ParameterBinding;
import io.testforge.api.explorer.RequestValueResolver;
import io.testforge.api.explorer.Schemas;
import io.testforge.api.explorer.ValueSource;
import java.util.Optional;

/**
 * Builds the control value for an array-valued parameter, which the explorer
 * deliberately declines to invent.
 *
 * <p>Exploration skips those parameters because an array needs a serialization
 * style, and sending the wrong one changes what the endpoint returns. Fuzzing
 * cannot skip them: an operation whose only interesting input is
 * {@code ?status=} would produce no cases at all, and a whole class of declared
 * constraints — {@code minItems}, {@code uniqueItems}, the item schema — would
 * never be tested against any API.
 *
 * <p>So the style is read and honoured here, and where it has no single-valued
 * wire form the parameter still resolves to nothing. Extending the resolver
 * rather than changing it keeps exploration's promise intact: it still invents
 * no array values.
 */
public class SerializingValueResolver extends RequestValueResolver {

    private final ConstraintAwareValueFactory values;

    public SerializingValueResolver(ApiExplorerProperties.ParameterProperties configured,
                                    ConstraintAwareValueFactory values) {
        super(configured, values);
        this.values = values;
    }

    @Override
    public Optional<ParameterBinding> resolve(ExplorableOperation operation, Parameter parameter) {
        // anything the document or a human states about the parameter still
        // wins: a configured override is already the wire form its author meant
        Optional<ParameterBinding> declared = super.resolve(operation, parameter);
        if (declared.isPresent()) {
            return declared;
        }

        Schema<?> schema = parameter.getSchema();
        if (!"array".equals(Schemas.type(schema)) || ParameterSerialization.unsupported(parameter).isPresent()) {
            return Optional.empty();
        }

        int size = Math.max(schema.getMinItems() == null ? 1 : schema.getMinItems(), 1);
        if (schema.getMaxItems() != null) {
            size = Math.min(size, schema.getMaxItems());
        }

        return ParameterSerialization.elements(schema, size, values)
                .map(elements -> new ParameterBinding(parameter.getName(), parameter.getIn(),
                        ValueSource.GENERATED, ParameterSerialization.join(parameter, elements)));
    }
}
