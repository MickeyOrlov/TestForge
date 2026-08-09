package io.testforge.api.fuzz;

import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.testforge.api.explorer.ExplorableOperation;
import io.testforge.api.explorer.Schemas;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Everything the document declares about an operation's inputs, listed
 * independently of what this module can do about it.
 *
 * <p>Listed by inspecting the schema directly rather than by asking what cases
 * were generated. That is the point: a constraint that produced no case is
 * exactly the thing worth reporting, and deriving the inventory from the cases
 * would make it impossible to notice.
 */
public class ConstraintInventory {

    private static final int MAX_DEPTH = 8;

    private final JsonBodyFactory bodyFactory;

    public ConstraintInventory(JsonBodyFactory bodyFactory) {
        this.bodyFactory = bodyFactory;
    }

    public List<DeclaredConstraint> of(ExplorableOperation operation, BodyPlan bodyPlan) {
        Set<DeclaredConstraint> declared = new LinkedHashSet<>();

        for (Parameter parameter : operation.parameters()) {
            String in = parameter.getIn();
            if (!"path".equals(in) && !"query".equals(in)) {
                continue;
            }
            String location = in + ":" + parameter.getName();
            if (Boolean.TRUE.equals(parameter.getRequired()) && "query".equals(in)) {
                declared.add(new DeclaredConstraint(location, "required"));
            }
            addSchema(declared, parameter.getSchema(), location, false, 0);
        }

        if (bodyPlan.declared() && bodyPlan.schema() != null) {
            addSchema(declared, bodyFactory.effective(bodyPlan.schema()), "$", true, 0);
        }
        return List.copyOf(declared);
    }

    private void addSchema(Set<DeclaredConstraint> declared, Schema<?> raw, String location,
                           boolean bodyContext, int depth) {
        if (raw == null || depth > MAX_DEPTH) {
            return;
        }
        if (raw.getOneOf() != null && !raw.getOneOf().isEmpty()) {
            declared.add(new DeclaredConstraint(location, "oneOf"));
            return;
        }
        if (raw.getAnyOf() != null && !raw.getAnyOf().isEmpty()) {
            declared.add(new DeclaredConstraint(location, "anyOf"));
            return;
        }

        Schema<?> schema = bodyFactory.effective(raw);
        if (schema == null) {
            return;
        }

        if (Schemas.type(schema) != null) {
            declared.add(new DeclaredConstraint(location, "type"));
        }
        if (schema.getEnum() != null && !schema.getEnum().isEmpty()) {
            declared.add(new DeclaredConstraint(location, "enum"));
        }
        if (schema.getMinLength() != null) {
            declared.add(new DeclaredConstraint(location, "minLength"));
        }
        if (schema.getMaxLength() != null) {
            declared.add(new DeclaredConstraint(location, "maxLength"));
        }
        if (schema.getPattern() != null) {
            declared.add(new DeclaredConstraint(location, "pattern"));
        }
        if (schema.getFormat() != null) {
            declared.add(new DeclaredConstraint(location, "format"));
        }
        SchemaFacts.inclusiveMinimum(schema).ifPresent(value ->
                declared.add(new DeclaredConstraint(location, "minimum")));
        SchemaFacts.inclusiveMaximum(schema).ifPresent(value ->
                declared.add(new DeclaredConstraint(location, "maximum")));
        SchemaFacts.exclusiveMinimum(schema).ifPresent(value ->
                declared.add(new DeclaredConstraint(location, "exclusiveMinimum")));
        SchemaFacts.exclusiveMaximum(schema).ifPresent(value ->
                declared.add(new DeclaredConstraint(location, "exclusiveMaximum")));
        SchemaFacts.multipleOf(schema).ifPresent(value ->
                declared.add(new DeclaredConstraint(location, "multipleOf")));
        if (schema.getMinItems() != null) {
            declared.add(new DeclaredConstraint(location, "minItems"));
        }
        if (schema.getMaxItems() != null) {
            declared.add(new DeclaredConstraint(location, "maxItems"));
        }
        // a URL cannot express a JSON null, so nullability is only a promise
        // inside a body
        if (bodyContext && Boolean.FALSE.equals(schema.getNullable()) ) {
            declared.add(new DeclaredConstraint(location, "nullable"));
        }

        if (schema.getItems() != null) {
            if (Schemas.type(schema.getItems()) != null) {
                declared.add(new DeclaredConstraint(location, "items.type"));
            }
            addSchema(declared, schema.getItems(), BodyPaths.element(location, 0), bodyContext, depth + 1);
        }

        Map<String, Schema> properties = schema.getProperties();
        if (properties == null) {
            return;
        }
        List<String> required = schema.getRequired() == null ? List.of() : schema.getRequired();
        properties.forEach((name, property) -> {
            String childLocation = BodyPaths.child(location, name);
            if (required.contains(name)) {
                declared.add(new DeclaredConstraint(childLocation, "required"));
            }
            addSchema(declared, property, childLocation, bodyContext, depth + 1);
        });
    }
}
