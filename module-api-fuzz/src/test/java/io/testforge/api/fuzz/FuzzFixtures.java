package io.testforge.api.fuzz;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.testforge.api.discovery.ApiDiscoveryProperties;
import io.testforge.api.discovery.ApiSpecSource;
import io.testforge.api.discovery.OpenApiSpecParser;
import io.testforge.api.explorer.ApiExplorerProperties;
import io.testforge.api.explorer.ExchangeExecutor;
import io.testforge.api.explorer.ExplorableOperation;
import io.testforge.api.explorer.ObservationFactory;
import io.testforge.api.explorer.OperationSelector;
import io.testforge.api.explorer.RequestPlanner;
import io.testforge.api.explorer.ResponseContractChecker;
import io.testforge.api.explorer.SafetyPolicy;
import io.testforge.http.Redactor;
import java.util.List;
import java.util.Map;

/** The demo document every test in this module works from. */
final class FuzzFixtures {

    static final String SPEC_ID = "demo";
    static final String LOCATION = "classpath:openapi/fuzz-demo.yaml";

    private FuzzFixtures() {
    }

    static List<ExplorableOperation> operations() {
        return new OperationSelector().select(SPEC_ID,
                new OpenApiSpecParser().parse(new ApiSpecSource(SPEC_ID, LOCATION)));
    }

    static ExplorableOperation operation(String operationId) {
        return operations().stream()
                .filter(operation -> operationId.equals(operation.operationId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No operation " + operationId));
    }

    static ApiFuzzRunner runner(ApiFuzzProperties properties, ExchangeExecutor executor) {
        return runner(properties, executor, Map.of());
    }

    /**
     * The full wiring, in one place.
     *
     * <p>It was copied into four tests, which meant every new collaborator on
     * the runner had to be added four times and a test could silently drift into
     * building a runner the auto-configuration would never produce.
     */
    static ApiFuzzRunner runner(ApiFuzzProperties properties, ExchangeExecutor executor,
                                Map<String, String> parameterDefaults) {

        ObjectMapper objectMapper = new ObjectMapper();
        JsonBodyFactory bodyFactory = new JsonBodyFactory(objectMapper);
        ConstraintInventory inventory = new ConstraintInventory(bodyFactory);
        ResponseClassifier classifier = new ResponseClassifier(
                new ResponseContractChecker(objectMapper), objectMapper);
        FindingConfirmer confirmer = new FindingConfirmer(executor, classifier, properties);

        return new ApiFuzzRunner(
                new OpenApiSpecParser(),
                new OperationSelector(),
                new SafetyPolicy(properties.methods(), properties.allowUnsafeMethods(),
                        properties.includePaths(), properties.excludePaths()),
                new RequestPlanner(new SerializingValueResolver(
                        new ApiExplorerProperties.ParameterProperties(parameterDefaults, Map.of()),
                        new ConstraintAwareValueFactory()), true),
                new FuzzCaseGenerator(),
                new BodyCaseGenerator(objectMapper, bodyFactory),
                new ProtocolCaseGenerator(),
                bodyFactory,
                new JsonBodyMutator(objectMapper),
                inventory,
                new BaselineSelfCheck(),
                confirmer,
                new RequestShrinker(objectMapper, inventory, bodyFactory, confirmer, properties),
                new ReproductionWriter(objectMapper),
                new FuzzCaseSelector(properties.seed(), properties.maxCasesPerOperation()),
                executor,
                classifier,
                new ObservationFactory(
                        new Redactor(objectMapper, List.of("authorization"), List.of("token")), 4000),
                objectMapper,
                new ApiDiscoveryProperties(true, null, null, null, null,
                        Map.of(SPEC_ID, new ApiDiscoveryProperties.Spec(LOCATION))),
                properties);
    }
}
