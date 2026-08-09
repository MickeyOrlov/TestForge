package io.testforge.api.fuzz;

import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.testforge.api.explorer.ExplorableOperation;
import io.testforge.api.explorer.Schemas;
import java.util.ArrayList;
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
    private final Compositions compositions;

    public ConstraintInventory(JsonBodyFactory bodyFactory) {
        this.bodyFactory = bodyFactory;
        this.compositions = new Compositions(bodyFactory::effective);
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

    /**
     * The promises this run decided not to test, with the reason for each.
     *
     * <p>Collected in the same pass shape as {@link #of}, and deliberately not
     * derived from "declared minus exercised": that subtraction cannot tell a
     * constraint nobody got to from one nothing could honestly attack, and the
     * two mean opposite things to a reader.
     */
    public List<UnsupportedConstraint> unsupported(ExplorableOperation operation, BodyPlan bodyPlan) {
        List<UnsupportedConstraint> unsupported = new ArrayList<>();

        for (Parameter parameter : operation.parameters()) {
            String in = parameter.getIn();
            if (!"path".equals(in) && !"query".equals(in)) {
                continue;
            }
            String location = in + ":" + parameter.getName();
            ParameterSerialization.unsupported(parameter).ifPresent(reason -> {
                // every promise about the parameter is out of reach at once —
                // the value never reaches the wire in the declared form, so none
                // of its constraints can be tested through it
                Set<DeclaredConstraint> promises = new LinkedHashSet<>();
                addSchema(promises, parameter.getSchema(), location, false, 0);
                promises.forEach(promise ->
                        unsupported.add(new UnsupportedConstraint(promise.location(), promise.constraint(), reason)));
                if (promises.isEmpty()) {
                    unsupported.add(new UnsupportedConstraint(location, "serialization", reason));
                }
            });
        }

        if (bodyPlan.declared() && !bodyPlan.usable() && bodyPlan.unsupportedReason() != null) {
            unsupported.add(new UnsupportedConstraint("$", "requestBody", bodyPlan.unsupportedReason()));
        }
        unsupported.addAll(bodyPlan.unsupported());
        return List.copyOf(unsupported);
    }

    private void addSchema(Set<DeclaredConstraint> declared, Schema<?> raw, String location,
                           boolean bodyContext, int depth) {
        if (raw == null || depth > MAX_DEPTH) {
            return;
        }
        if (Compositions.branching(raw)) {
            declared.add(new DeclaredConstraint(location, Compositions.keyword(raw)));
            // a pinned discriminator makes the chosen branch's own promises
            // testable, so they belong in the inventory rather than vanishing
            // behind the composition that contains them
            Compositions.Choice choice = compositions.choose(raw);
            if (choice.fuzzable()) {
                addSchema(declared, choice.branch(), location, bodyContext, depth + 1);
            }
            return;
        }

        Schema<?> schema = bodyFactory.effective(raw);
        if (schema == null) {
            return;
        }
        if (SchemaFacts.readOnly(schema)) {
            declared.add(new DeclaredConstraint(location, "readOnly"));
            return;
        }
        if (bodyContext && SchemaFacts.additionalPropertiesForbidden(schema)) {
            declared.add(new DeclaredConstraint(location, "additionalProperties"));
        }
        if (bodyContext && SchemaFacts.uniqueItems(schema)) {
            declared.add(new DeclaredConstraint(location, "uniqueItems"));
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
