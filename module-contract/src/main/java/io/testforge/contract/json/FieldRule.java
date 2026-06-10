package io.testforge.contract.json;

public record FieldRule(String path, FieldType type, boolean required, boolean nullable) {

    public FieldRule {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("path must not be blank");
        }
        if (type == null) {
            type = FieldType.ANY;
        }
    }
}
