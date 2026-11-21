package io.testforge.security;

import static io.testforge.security.BusinessInvariant.Value.binding;
import static io.testforge.security.BusinessInvariant.Value.item;
import static io.testforge.security.BusinessInvariant.Value.literal;
import static io.testforge.security.BusinessInvariant.Value.operation;
import static io.testforge.security.BusinessInvariant.Value.project;
import static io.testforge.security.BusinessInvariant.Value.response;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.TextNode;

import io.testforge.artifact.ArtifactSink;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

class BusinessInvariantTest {
    final ObjectMapper mapper =
            new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    final BusinessInvariantEvaluator engine = new BusinessInvariantEvaluator();

    BusinessInvariant.Value n(String value) {
        return literal(DecimalNode.valueOf(new BigDecimal(value)));
    }

    @Test
    void sumsProductsAndScaleComparisonsUseExactDecimalValues() throws Exception {
        var total =
                operation(
                        BusinessInvariant.Source.SUM,
                        response("/lines"),
                        operation(BusinessInvariant.Source.MULTIPLY, item("/qty"), item("/price")));
        var body =
                mapper.readTree(
                        "{\"lines\":[{\"qty\":3,\"price\":0.1},{\"qty\":1,\"price\":0.2}],\"total\":0.5000}");
        assertThat(
                        engine.evaluate(
                                new BusinessInvariant(
                                        "sum",
                                        BusinessInvariant.Compare.NUMERIC_EQUALS,
                                        total,
                                        response("/total")),
                                body,
                                Map.of()))
                .isEqualTo(BusinessInvariantEvaluator.Outcome.PASS);
        assertThat(
                        engine.evaluate(
                                new BusinessInvariant(
                                        "sum",
                                        BusinessInvariant.Compare.NUMERIC_EQUALS,
                                        total,
                                        n("0.5000000000000000000001")),
                                body,
                                Map.of()))
                .isEqualTo(BusinessInvariantEvaluator.Outcome.FAIL);
    }

    @Test
    void resourceDeltasAndRefundCapsUseCapturedValues() throws Exception {
        var body = mapper.readTree("{\"balance\":9.6,\"refund\":0.8}");
        assertThat(
                        engine.evaluate(
                                new BusinessInvariant(
                                        "charge",
                                        BusinessInvariant.Compare.NUMERIC_EQUALS,
                                        response("/balance"),
                                        operation(
                                                BusinessInvariant.Source.SUBTRACT,
                                                binding("before"),
                                                n("0.4"))),
                                body,
                                Map.of("before", DecimalNode.valueOf(new BigDecimal("10.00")))))
                .isEqualTo(BusinessInvariantEvaluator.Outcome.PASS);
        assertThat(
                        engine.evaluate(
                                new BusinessInvariant(
                                        "cap",
                                        BusinessInvariant.Compare.AT_MOST,
                                        response("/refund"),
                                        n("0.4")),
                                body,
                                Map.of()))
                .isEqualTo(BusinessInvariantEvaluator.Outcome.FAIL);
    }

    @Test
    void numbersAreNotImplicitlyCoercedFromStrings() throws Exception {
        var body = mapper.readTree("{\"amount\":\"12.30\"}");
        assertThat(
                        engine.evaluate(
                                new BusinessInvariant(
                                        "amount",
                                        BusinessInvariant.Compare.NUMERIC_EQUALS,
                                        response("/amount"),
                                        n("12.3")),
                                body,
                                Map.of()))
                .isEqualTo(BusinessInvariantEvaluator.Outcome.INCOMPLETE);
        assertThat(
                        engine.evaluate(
                                new BusinessInvariant(
                                        "amount",
                                        BusinessInvariant.Compare.NUMERIC_EQUALS,
                                        operation(
                                                BusinessInvariant.Source.DECIMAL,
                                                response("/amount")),
                                        n("12.3")),
                                body,
                                Map.of()))
                .isEqualTo(BusinessInvariantEvaluator.Outcome.PASS);
    }

    @Test
    void collectionMembershipIsolationAndCountsHaveTypedSemantics() throws Exception {
        var body =
                mapper.readTree(
                        "{\"items\":[{\"id\":\"owned\",\"tenant\":\"a\"},{\"id\":\"foreign\",\"tenant\":\"b\"}]}");
        assertThat(
                        engine.evaluate(
                                new BusinessInvariant(
                                        "owned",
                                        BusinessInvariant.Compare.CONTAINS,
                                        project(response("/items"), "/id"),
                                        binding("owned")),
                                body,
                                Map.of("owned", TextNode.valueOf("owned"))))
                .isEqualTo(BusinessInvariantEvaluator.Outcome.PASS);
        assertThat(
                        engine.evaluate(
                                new BusinessInvariant(
                                        "isolated",
                                        BusinessInvariant.Compare.ALL_EQUAL,
                                        project(response("/items"), "/tenant"),
                                        literal(TextNode.valueOf("a"))),
                                body,
                                Map.of()))
                .isEqualTo(BusinessInvariantEvaluator.Outcome.FAIL);
        assertThat(
                        engine.evaluate(
                                new BusinessInvariant(
                                        "count",
                                        BusinessInvariant.Compare.NUMERIC_EQUALS,
                                        operation(
                                                BusinessInvariant.Source.COUNT, response("/items")),
                                        n("2")),
                                body,
                                Map.of()))
                .isEqualTo(BusinessInvariantEvaluator.Outcome.PASS);
    }

    @Test
    void missingOperandsInvalidRowsAndHugeDecimalsAreIncompleteWithoutEchoingValues()
            throws Exception {
        var rule =
                new BusinessInvariant(
                        "rule",
                        BusinessInvariant.Compare.NUMERIC_EQUALS,
                        response("/missing"),
                        binding("private"));
        assertThat(engine.evaluate(rule, mapper.readTree("{}"), Map.of()))
                .isEqualTo(BusinessInvariantEvaluator.Outcome.INCOMPLETE);
        assertThat(
                        engine.evaluate(
                                new BusinessInvariant(
                                        "row",
                                        BusinessInvariant.Compare.NUMERIC_EQUALS,
                                        item("/amount"),
                                        n("1")),
                                mapper.readTree("{}"),
                                Map.of()))
                .isEqualTo(BusinessInvariantEvaluator.Outcome.INCOMPLETE);
        assertThat(
                        engine.evaluate(
                                new BusinessInvariant(
                                        "huge",
                                        BusinessInvariant.Compare.NUMERIC_EQUALS,
                                        n("1E+100000"),
                                        n("1")),
                                mapper.readTree("{}"),
                                Map.of()))
                .isEqualTo(BusinessInvariantEvaluator.Outcome.INCOMPLETE);
        String secret = "synthetic-private-value";
        assertThat(literal(TextNode.valueOf(secret)).toString()).doesNotContain(secret);
    }

    @Test
    void expressionsAndCollectionWorkAreBounded() throws Exception {
        var deep = n("1");
        for (int i = 0; i < 40; i++) deep = operation(BusinessInvariant.Source.ADD, deep, n("1"));
        assertThat(
                        engine.evaluate(
                                new BusinessInvariant(
                                        "deep",
                                        BusinessInvariant.Compare.NUMERIC_EQUALS,
                                        deep,
                                        n("1")),
                                mapper.readTree("{}"),
                                Map.of()))
                .isEqualTo(BusinessInvariantEvaluator.Outcome.INCOMPLETE);
        var array = mapper.createArrayNode();
        for (int i = 0; i < 10001; i++) array.add(1);
        assertThat(
                        engine.evaluate(
                                new BusinessInvariant(
                                        "large",
                                        BusinessInvariant.Compare.ALL_EQUAL,
                                        literal(array),
                                        n("1")),
                                mapper.readTree("{}"),
                                Map.of()))
                .isEqualTo(BusinessInvariantEvaluator.Outcome.INCOMPLETE);
    }

    @Test
    void caseCoverageCannotResolveAnInvariantThatWasNotRechecked(@TempDir Path dir) {
        String check = "business-business-invariant",
                target = "suite orders version v1 case charge POST /orders/{id}/pay role owner";
        var props =
                new SecurityProperties(
                        null,
                        dir.resolve("reports").toString(),
                        dir.resolve("baseline.json").toString(),
                        List.of(),
                        Set.of(Severity.HIGH),
                        null);
        var first = new SecurityCollector(100);
        first.covered(check, target + " invariant total", "owner", "measured", null);
        first.finding(
                check,
                Severity.HIGH,
                target + " invariant total",
                "declared business invariant was violated",
                "API6:2023");
        new SecurityRunner(props, first, ArtifactSink.NO_OP, () -> true).writeBaseline();
        var skipped = new SecurityCollector(100);
        skipped.covered(check, target, "owner", "measured", null);
        var incomplete = new SecurityRunner(props, skipped, ArtifactSink.NO_OP, () -> true).run();
        assertThat(incomplete.complete()).isFalse();
        assertThat(incomplete.findings()).noneMatch(f -> f.status().equals("resolved"));
        var checked = new SecurityCollector(100);
        checked.covered(check, target + " invariant total", "owner", "measured", null);
        assertThat(
                        new SecurityRunner(props, checked, ArtifactSink.NO_OP, () -> true)
                                .run()
                                .findings())
                .anyMatch(f -> f.status().equals("resolved"));
    }

    @Test
    void typedRulesRoundTripWithoutChangingTheirEvaluation() throws Exception {
        var rule =
                new BusinessInvariant(
                        "roundtrip",
                        BusinessInvariant.Compare.NUMERIC_EQUALS,
                        operation(
                                BusinessInvariant.Source.ADD, response("/amount"), binding("fee")),
                        n("0.30"));
        var restored = mapper.readValue(mapper.writeValueAsBytes(rule), BusinessInvariant.class);
        assertThat(
                        engine.evaluate(
                                restored,
                                mapper.readTree("{\"amount\":0.10}"),
                                Map.of("fee", DecimalNode.valueOf(new BigDecimal("0.20")))))
                .isEqualTo(BusinessInvariantEvaluator.Outcome.PASS);
    }
}
