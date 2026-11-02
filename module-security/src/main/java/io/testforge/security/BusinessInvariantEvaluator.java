package io.testforge.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import io.testforge.security.BusinessInvariant.Value;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Exact decimal comparisons. Unavailable/invalid operands are incomplete evidence, not violations.
 */
public final class BusinessInvariantEvaluator {
    public enum Outcome {
        PASS,
        FAIL,
        INCOMPLETE
    }

    private static final int MAX_DEPTH = 32, MAX_NODES = 512, MAX_ITEMS = 10000, MAX_WORK = 100000;
    private int work;

    public synchronized Outcome evaluate(
            BusinessInvariant invariant, JsonNode response, Map<String, JsonNode> bindings) {
        work = 0;
        try {
            validate(invariant.actual(), 0, new int[] {0}, false);
            validate(invariant.expected(), 0, new int[] {0}, false);
            JsonNode a = value(invariant.actual(), response, bindings, null, 0);
            JsonNode b = value(invariant.expected(), response, bindings, null, 0);
            bounded(a, 0);
            bounded(b, 0);
            boolean pass =
                    switch (invariant.compare()) {
                        case NUMERIC_EQUALS -> decimal(a).compareTo(decimal(b)) == 0;
                        case AT_MOST -> decimal(a).compareTo(decimal(b)) <= 0;
                        case AT_LEAST -> decimal(a).compareTo(decimal(b)) >= 0;
                        case LESS_THAN -> decimal(a).compareTo(decimal(b)) < 0;
                        case GREATER_THAN -> decimal(a).compareTo(decimal(b)) > 0;
                        case JSON_EQUALS -> a.equals(b);
                        case ALL_EQUAL -> {
                            array(a);
                            boolean equal = true;
                            for (JsonNode item : a) {
                                tick();
                                equal &= item.equals(b);
                            }
                            yield equal;
                        }
                        case CONTAINS -> {
                            array(a);
                            boolean found = false;
                            for (JsonNode item : a) {
                                tick();
                                found |= item.equals(b);
                            }
                            yield found;
                        }
                    };
            return pass ? Outcome.PASS : Outcome.FAIL;
        } catch (RuntimeException e) {
            return Outcome.INCOMPLETE;
        }
    }

    static void validate(Value value, int depth, int[] nodes, boolean row) {
        if (depth > MAX_DEPTH || ++nodes[0] > MAX_NODES)
            throw new BusinessSecurityException("invariant expression exceeds structural bounds");
        if (value.source() == BusinessInvariant.Source.ITEM && !row)
            throw new BusinessSecurityException("item operand must be inside a sum row expression");
        for (int i = 0; i < value.args().size(); i++)
            validate(
                    value.args().get(i),
                    depth + 1,
                    nodes,
                    row || value.source() == BusinessInvariant.Source.SUM && i == 1);
    }

    private JsonNode value(
            Value expression,
            JsonNode response,
            Map<String, JsonNode> bindings,
            JsonNode row,
            int depth) {
        tick();
        if (depth > MAX_DEPTH)
            throw new BusinessSecurityException("invariant expression exceeds depth");
        return switch (expression.source()) {
            case RESPONSE -> at(response, expression.pointer());
            case BINDING -> at(bindings.get(expression.binding()), expression.pointer());
            case ITEM -> at(row, expression.pointer());
            case LITERAL -> expression.literal();
            case ADD, SUBTRACT, MULTIPLY -> {
                BigDecimal a =
                        decimal(
                                value(
                                        expression.args().get(0),
                                        response,
                                        bindings,
                                        row,
                                        depth + 1));
                BigDecimal b =
                        decimal(
                                value(
                                        expression.args().get(1),
                                        response,
                                        bindings,
                                        row,
                                        depth + 1));
                BigDecimal result =
                        switch (expression.source()) {
                            case ADD -> a.add(b);
                            case SUBTRACT -> a.subtract(b);
                            default -> a.multiply(b);
                        };
                yield number(result);
            }
            case DECIMAL -> {
                JsonNode item =
                        value(expression.args().getFirst(), response, bindings, row, depth + 1);
                if (item.isTextual()) {
                    String text = item.textValue();
                    if (text.length() > 98
                            || !text.matches("[+-]?(0|[1-9][0-9]{0,63})(\\.[0-9]{1,32})?"))
                        throw new BusinessSecurityException("unsupported decimal string");
                    yield number(new BigDecimal(text));
                }
                yield number(decimal(item));
            }
            case COUNT -> {
                JsonNode items =
                        value(expression.args().getFirst(), response, bindings, row, depth + 1);
                array(items);
                yield number(BigDecimal.valueOf(items.size()));
            }
            case PROJECT -> {
                JsonNode items =
                        value(expression.args().getFirst(), response, bindings, row, depth + 1);
                array(items);
                var projected = JsonNodeFactory.instance.arrayNode();
                for (JsonNode item : items) {
                    tick();
                    projected.add(at(item, expression.pointer()));
                }
                yield projected;
            }
            case SUM -> {
                JsonNode items =
                        value(expression.args().getFirst(), response, bindings, row, depth + 1);
                array(items);
                BigDecimal total = BigDecimal.ZERO;
                for (JsonNode item : items) {
                    tick();
                    JsonNode amount =
                            expression.args().size() == 1
                                    ? item
                                    : value(
                                            expression.args().get(1),
                                            response,
                                            bindings,
                                            item,
                                            depth + 1);
                    total = total.add(decimal(amount));
                    number(total);
                }
                yield number(total);
            }
        };
    }

    private JsonNode at(JsonNode node, String pointer) {
        if (node == null) throw new BusinessSecurityException("invariant source unavailable");
        JsonNode value = node.at(pointer);
        if (value.isMissingNode())
            throw new BusinessSecurityException("invariant pointer unavailable");
        return value;
    }

    private void bounded(JsonNode value, int depth) {
        tick();
        if (depth > MAX_DEPTH
                || value == null
                || value.isMissingNode()
                || value.isTextual() && value.textValue().length() > 65536)
            throw new BusinessSecurityException("invariant value exceeds supported bounds");
        if (value.isArray()) array(value);
        if (value.isContainerNode()) value.forEach(child -> bounded(child, depth + 1));
    }

    private void array(JsonNode value) {
        if (!value.isArray() || value.size() > MAX_ITEMS)
            throw new BusinessSecurityException("invariant needs a bounded array");
    }

    private BigDecimal decimal(JsonNode value) {
        if (!value.isNumber())
            throw new BusinessSecurityException(
                    "invariant needs a JSON number or explicit decimal conversion");
        BigDecimal number = value.decimalValue();
        number(number);
        return number;
    }

    private JsonNode number(BigDecimal number) {
        if (number.precision() > 64 || number.scale() > 32 || number.scale() < -32)
            throw new BusinessSecurityException("invariant decimal exceeds supported bounds");
        return DecimalNode.valueOf(number);
    }

    private void tick() {
        if (++work > MAX_WORK)
            throw new BusinessSecurityException("invariant work budget exhausted");
    }
}
