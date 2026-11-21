package io.testforge.security;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/** Typed, bounded arithmetic and collection rules; no scripts or implicit numeric coercion. */
public record BusinessInvariant(String id, Compare compare, Value actual, Value expected) {
    public enum Compare {
        NUMERIC_EQUALS,
        AT_MOST,
        AT_LEAST,
        LESS_THAN,
        GREATER_THAN,
        JSON_EQUALS,
        ALL_EQUAL,
        CONTAINS
    }

    public enum Source {
        RESPONSE,
        BINDING,
        ITEM,
        LITERAL,
        ADD,
        SUBTRACT,
        MULTIPLY,
        SUM,
        COUNT,
        PROJECT,
        DECIMAL
    }

    public BusinessInvariant {
        id = BusinessSecuritySuite.identifier(id);
        if (compare == null || actual == null || expected == null)
            throw new BusinessSecurityException("invariant needs comparison and operands");
    }

    @Override
    public String toString() {
        return "BusinessInvariant[" + id + "]";
    }

    public record Value(
            Source source, String pointer, String binding, JsonNode literal, List<Value> args) {
        public Value {
            if (source == null)
                throw new BusinessSecurityException("invariant operand needs a source");
            if (source != Source.LITERAL && literal != null && literal.isNull()) literal = null;
            args = List.copyOf(args == null ? List.of() : args);
            pointer = pointer == null ? "" : pointer;
            BusinessSecuritySuite.pointer(pointer);
            if (source == Source.BINDING) BusinessSecuritySuite.identifier(binding);
            else if (binding != null)
                throw new BusinessSecurityException(
                        "binding alias only applies to binding operands");
            if (source == Source.LITERAL && literal == null
                    || source != Source.LITERAL && literal != null)
                throw new BusinessSecurityException(
                        "literal value only applies to literal operands");
            int min =
                    switch (source) {
                        case ADD, SUBTRACT, MULTIPLY -> 2;
                        case SUM, COUNT, PROJECT, DECIMAL -> 1;
                        default -> 0;
                    };
            int max = source == Source.SUM ? 2 : min;
            if (args.size() < min || args.size() > max)
                throw new BusinessSecurityException("invariant operand has unsupported arity");
            if (!List.of(Source.RESPONSE, Source.BINDING, Source.ITEM, Source.PROJECT)
                            .contains(source)
                    && !pointer.isEmpty())
                throw new BusinessSecurityException("pointer is not supported for this operand");
        }

        public static Value response(String pointer) {
            return new Value(Source.RESPONSE, pointer, null, null, null);
        }

        public static Value binding(String alias) {
            return new Value(Source.BINDING, "", alias, null, null);
        }

        public static Value item(String pointer) {
            return new Value(Source.ITEM, pointer, null, null, null);
        }

        public static Value literal(JsonNode literal) {
            return new Value(Source.LITERAL, "", null, literal, null);
        }

        public static Value operation(Source source, Value... args) {
            return new Value(source, "", null, null, List.of(args));
        }

        public static Value project(Value array, String pointer) {
            return new Value(Source.PROJECT, pointer, null, null, List.of(array));
        }

        @Override
        public String toString() {
            return "BusinessInvariantValue[" + source + ",redacted]";
        }
    }
}
