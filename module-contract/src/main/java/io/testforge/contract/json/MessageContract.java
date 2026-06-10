package io.testforge.contract.json;

import java.util.ArrayList;
import java.util.List;

public record MessageContract(String name, List<FieldRule> rules) {

    public MessageContract {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        rules = List.copyOf(rules == null ? List.of() : rules);
    }

    public static Builder named(String name) {
        return new Builder(name);
    }

    public static final class Builder {
        private final String name;
        private final List<FieldRule> rules = new ArrayList<>();

        private Builder(String name) {
            this.name = name;
        }

        public Builder required(String path, FieldType type) {
            rules.add(new FieldRule(path, type, true, false));
            return this;
        }

        public Builder optional(String path, FieldType type) {
            rules.add(new FieldRule(path, type, false, false));
            return this;
        }

        public Builder nullable(String path, FieldType type) {
            rules.add(new FieldRule(path, type, false, true));
            return this;
        }

        public MessageContract build() {
            return new MessageContract(name, rules);
        }
    }
}
