package io.testforge.api.fuzz;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.models.media.Schema;
import io.testforge.api.explorer.ExplorableOperation;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The v1.4 rule: for every supported constraint, a provably valid control and a
 * mutation that breaks that constraint and no other — or an explicit refusal
 * with the reason.
 *
 * <p>Half of these assert what the module <em>declines</em> to do, which is the
 * half that keeps it honest. A fuzzer that always produces a case will
 * eventually produce one it cannot defend, and a finding nobody can defend costs
 * more than the bug it was supposed to replace.
 */
class MutationSemanticsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JsonBodyFactory factory = new JsonBodyFactory(MAPPER);
    private final BodyCaseGenerator generator = new BodyCaseGenerator(MAPPER, factory);

    // --- arrays -----------------------------------------------------------

    @Test
    void aUniqueItemsArrayGetsDistinctElementsRatherThanCopies() {
        JsonNode body = baseline("createAccount");

        JsonNode labels = body.path("labels");
        assertThat(labels).hasSize(2);
        assertThat(labels.get(0).asText()).isNotEqualTo(labels.get(1).asText());
        // and the elements still satisfy their own minLength
        labels.forEach(label -> assertThat(label.asText().length()).isGreaterThanOrEqualTo(3));
    }

    @Test
    void uniqueItemsProducesADuplicateCaseThatBreaksNothingElse() {
        FuzzCase duplicate = bodyCase("createAccount", "$.labels", FuzzCaseKind.DUPLICATE_ITEM);

        assertThat(duplicate.expectation()).isEqualTo(FuzzExpectation.REJECT);
        assertThat(duplicate.constraint()).isEqualTo("uniqueItems");

        JsonNode mutated = mutate("createAccount", duplicate);
        JsonNode labels = mutated.path("labels");
        // still within minItems..maxItems, so only uniqueItems is violated
        assertThat(labels).hasSize(2);
        assertThat(labels.get(0)).isEqualTo(labels.get(1));
    }

    @Test
    void anArrayWithNoUniqueItemsDeclaredGetsNoDuplicateCase() {
        assertThat(bodyCases("createUser"))
                .noneMatch(fuzzCase -> fuzzCase.kind() == FuzzCaseKind.DUPLICATE_ITEM);
    }

    // --- objects ----------------------------------------------------------

    @Test
    void additionalPropertiesFalseIsProvenByAnUndeclaredProperty() {
        FuzzCase undeclared = bodyCase("createAccount", "$", FuzzCaseKind.UNDECLARED_PROPERTY);

        assertThat(undeclared.expectation()).isEqualTo(FuzzExpectation.REJECT);
        assertThat(undeclared.constraint()).isEqualTo("additionalProperties");
        assertThat(mutate("createAccount", undeclared).has(BodyCaseGenerator.UNDECLARED_PROPERTY)).isTrue();
    }

    @Test
    void aSchemaSilentOnAdditionalPropertiesEarnsNoSuchCase() {
        // absent additionalProperties permits extras outright: accusing a
        // service of accepting one would be accusing it of obeying its document
        assertThat(bodyCases("createUser"))
                .noneMatch(fuzzCase -> fuzzCase.kind() == FuzzCaseKind.UNDECLARED_PROPERTY);
    }

    @Test
    void aReadOnlyPropertyIsLeftOutOfTheControlAndProbedSeparately() {
        assertThat(baseline("createAccount").has("id")).isFalse();

        FuzzCase readOnly = bodyCase("createAccount", "$.id", FuzzCaseKind.READ_ONLY_IN_REQUEST);
        // OpenAPI says SHOULD NOT, not MUST NOT — so a service accepting it is
        // not breaking a promise, and only a crash would be a finding
        assertThat(readOnly.expectation()).isEqualTo(FuzzExpectation.UNSPECIFIED);
        assertThat(readOnly.constraint()).isEqualTo("readOnly");
        assertThat(mutate("createAccount", readOnly).path("id").isTextual()).isTrue();
    }

    @Test
    void nestedItemConstraintsAreReachedThroughTheirArray() {
        assertThat(bodyCases("createAccount"))
                .filteredOn(fuzzCase -> "$.labels[0]".equals(fuzzCase.location()))
                .isNotEmpty()
                .anySatisfy(fuzzCase -> {
                    assertThat(fuzzCase.kind()).isEqualTo(FuzzCaseKind.TOO_SHORT);
                    assertThat(fuzzCase.constraint()).isEqualTo("minLength");
                });
    }

    // --- compositions -----------------------------------------------------

    @Test
    void aDiscriminatedCompositionIsFuzzedInsideTheBranchItPins() {
        JsonBodyFactory.Baseline baseline = factory.build(schema("createAccount"));

        assertThat(baseline.usable()).isTrue();
        assertThat(baseline.body().path("method").path("kind").asText()).isEqualTo("card");
        // the branch is pinned, so its own fields are provable
        assertThat(bodyCases("createAccount"))
                .anyMatch(fuzzCase -> "$.method.card".equals(fuzzCase.location())
                        && fuzzCase.expectation() == FuzzExpectation.REJECT);
    }

    @Test
    void theDiscriminatorItselfIsNeverMutated() {
        // changing it selects another branch, so the mutant would be judged
        // against a schema the case was never derived from
        assertThat(factory.build(schema("createAccount")).unfuzzablePaths()).contains("$.method.kind");
        assertThat(bodyCases("createAccount"))
                .noneMatch(fuzzCase -> "$.method.kind".equals(fuzzCase.location()));
    }

    @Test
    void anUndiscriminatedCompositionIsRefusedWithItsReason() {
        JsonBodyFactory.Baseline baseline = factory.build(schema("createUser"));

        assertThat(baseline.usable()).isTrue();
        assertThat(baseline.unfuzzablePaths()).contains("$.payment");
        assertThat(baseline.unsupported())
                .singleElement()
                .satisfies(unsupported -> {
                    assertThat(unsupported.location()).isEqualTo("$.payment");
                    assertThat(unsupported.constraint()).isEqualTo("oneOf");
                    assertThat(unsupported.reason()).contains("no discriminator");
                });
        assertThat(bodyCases("createUser"))
                .noneMatch(fuzzCase -> fuzzCase.location().startsWith("$.payment"));
    }

    // --- parameter serialization -----------------------------------------

    @Test
    void anArrayParameterIsSerializedAccordingToItsStyle() {
        List<FuzzCase> cases = new FuzzCaseGenerator().generate(FuzzFixtures.operation("listReports"));

        // style form + explode false: one name, elements joined with commas
        assertThat(cases)
                .filteredOn(fuzzCase -> fuzzCase.kind() == FuzzCaseKind.AT_UPPER_BOUND)
                .singleElement()
                .satisfies(fuzzCase -> assertThat(fuzzCase.value()).isEqualTo("open,closed,archived"));

        // uniqueItems is honoured while building the array, not only asserted
        // about it: the three elements are three different enum members
        assertThat(cases)
                .filteredOn(fuzzCase -> fuzzCase.kind() == FuzzCaseKind.DUPLICATE_ITEM)
                .singleElement()
                .satisfies(fuzzCase -> assertThat(fuzzCase.value()).isEqualTo("open,open"));
    }

    @Test
    void anArrayCaseThatWouldNeedAnImpossibleElementIsNotGeneratedAtAll() {
        // maxItems is 3, uniqueItems is true, and the item enum has exactly
        // three members — so no fourth distinct element exists. Padding the
        // array with a repeat would violate uniqueItems too, and a case that
        // breaks two constraints cannot say which one the service objected to
        assertThat(new FuzzCaseGenerator().generate(FuzzFixtures.operation("listReports")))
                .noneMatch(fuzzCase -> fuzzCase.kind() == FuzzCaseKind.TOO_MANY_ITEMS);
    }

    @Test
    void anExplodedArrayParameterIsRefusedRatherThanCommaJoined() {
        var parameter = FuzzFixtures.operation("listReports").parameters().stream()
                .filter(candidate -> "owner".equals(candidate.getName()))
                .findFirst()
                .orElseThrow();

        Optional<String> reason = ParameterSerialization.unsupported(parameter);
        assertThat(reason).isPresent();
        assertThat(reason.get()).contains("explode=true");

        assertThat(new FuzzCaseGenerator().generate(FuzzFixtures.operation("listReports")))
                .noneMatch(fuzzCase -> "owner".equals(fuzzCase.parameterName()));
    }

    @Test
    void anObjectParameterIsRefusedRatherThanGuessedAt() {
        var parameter = FuzzFixtures.operation("listReports").parameters().stream()
                .filter(candidate -> "filter".equals(candidate.getName()))
                .findFirst()
                .orElseThrow();

        assertThat(ParameterSerialization.unsupported(parameter))
                .get()
                .asString()
                .contains("object-valued");
    }

    @Test
    void everyRefusalReachesTheCoverageReportWithItsReason() {
        ExplorableOperation operation = FuzzFixtures.operation("listReports");
        BodyPlan bodyPlan = BodyPlan.from(operation.operation(), factory);

        List<UnsupportedConstraint> unsupported =
                new ConstraintInventory(factory).unsupported(operation, bodyPlan);

        assertThat(unsupported)
                .isNotEmpty()
                .allSatisfy(entry -> assertThat(entry.reason()).isNotBlank());
        assertThat(unsupported)
                .anyMatch(entry -> entry.location().startsWith("query:owner"))
                .anyMatch(entry -> entry.location().startsWith("query:filter"));
    }

    // --- helpers ----------------------------------------------------------

    private JsonNode baseline(String operationId) {
        JsonBodyFactory.Baseline baseline = factory.build(schema(operationId));
        assertThat(baseline.unsupportedReason()).isNull();
        return baseline.body();
    }

    private List<FuzzCase> bodyCases(String operationId) {
        ExplorableOperation operation = FuzzFixtures.operation(operationId);
        Set<String> unfuzzable = factory.build(schema(operationId)).unfuzzablePaths();
        return generator.generate(operation, schema(operationId), unfuzzable);
    }

    private FuzzCase bodyCase(String operationId, String location, FuzzCaseKind kind) {
        return bodyCases(operationId).stream()
                .filter(fuzzCase -> location.equals(fuzzCase.location()) && fuzzCase.kind() == kind)
                .findFirst()
                .orElseThrow(() -> new AssertionError("No " + kind + " case at " + location));
    }

    private JsonNode mutate(String operationId, FuzzCase fuzzCase) {
        String mutated = new JsonBodyMutator(MAPPER).apply(baseline(operationId), fuzzCase)
                .orElseThrow(() -> new AssertionError("The case did not apply: " + fuzzCase.id()));
        try {
            return MAPPER.readTree(mutated);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private Schema<?> schema(String operationId) {
        return FuzzFixtures.operation(operationId).operation()
                .getRequestBody().getContent().get("application/json").getSchema();
    }
}
