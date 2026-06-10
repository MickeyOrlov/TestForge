package io.testforge.contract.json;

import com.fasterxml.jackson.databind.JsonNode;

public enum FieldType {
    ANY {
        @Override
        public boolean matches(JsonNode node) {
            return true;
        }
    },
    STRING {
        @Override
        public boolean matches(JsonNode node) {
            return node.isTextual();
        }
    },
    NUMBER {
        @Override
        public boolean matches(JsonNode node) {
            return node.isNumber();
        }
    },
    INTEGER {
        @Override
        public boolean matches(JsonNode node) {
            return node.isIntegralNumber();
        }
    },
    BOOLEAN {
        @Override
        public boolean matches(JsonNode node) {
            return node.isBoolean();
        }
    },
    OBJECT {
        @Override
        public boolean matches(JsonNode node) {
            return node.isObject();
        }
    },
    ARRAY {
        @Override
        public boolean matches(JsonNode node) {
            return node.isArray();
        }
    };

    public abstract boolean matches(JsonNode node);
}
