package io.testforge.api.discovery;

import io.testforge.api.discovery.ResponseSchemaLocator.LocatedSchema;
import io.testforge.contract.json.ContractViolation;
import io.testforge.contract.json.JsonContractValidator;
import io.testforge.contract.json.SchemaContract;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Compares one response against the schema its own document declares.
 *
 * <p>The fourth drift layer, next to the three the template already has: the
 * compiler catches DTO drift, {@code SchemaValidator} catches database drift,
 * {@code module-contract} catches payload drift on the wire — and this catches
 * the document lying about any of them.
 */
public class SpecDriftChecker {

    private static final Logger log = LoggerFactory.getLogger(SpecDriftChecker.class);

    private final ResponseSchemaLocator locator;
    private final OpenApiSchemaAdapter adapter;
    private final DeclaredPathProjector projector;
    private final JsonContractValidator validator;

    public SpecDriftChecker(ResponseSchemaLocator locator, OpenApiSchemaAdapter adapter,
                            DeclaredPathProjector projector, JsonContractValidator validator) {
        this.locator = locator;
        this.adapter = adapter;
        this.projector = projector;
        this.validator = validator;
    }

    public SpecDrift check(OpenApiDocument document, EndpointDescriptor endpoint,
                           ResponseSample sample, Map<String, String> observedShape) {

        Optional<LocatedSchema> located =
                locator.locate(document, endpoint, sample.status(), sample.contentType());
        if (located.isEmpty()) {
            return SpecDrift.notDeclared();
        }

        LocatedSchema schema = located.get();
        List<ContractViolation> violations = validate(document, endpoint, schema, sample);
        List<String> undeclared = undeclared(document, schema, observedShape);

        return new SpecDrift(true, schema.ref(), violations, undeclared);
    }

    private List<ContractViolation> validate(OpenApiDocument document, EndpointDescriptor endpoint,
                                             LocatedSchema schema, ResponseSample sample) {
        try {
            SchemaContract contract = adapter.toContract(document, schema.schema(), endpoint.artifactName());
            return validator.validate(sample.body(), contract);
        } catch (RuntimeException e) {
            log.debug("Could not validate {} against its declared schema: {}", endpoint.label(), e.toString());
            return List.of(new ContractViolation(endpoint.artifactName(), "$", "schema-unusable", e.getMessage()));
        }
    }

    private List<String> undeclared(OpenApiDocument document, LocatedSchema schema,
                                    Map<String, String> observedShape) {
        try {
            Set<String> declared = projector.project(document, schema.schema());
            return observedShape.keySet().stream()
                    .filter(path -> !declared.contains(path))
                    .sorted()
                    .toList();
        } catch (RuntimeException e) {
            log.debug("Could not project the declared schema: {}", e.toString());
            return List.of();
        }
    }
}
