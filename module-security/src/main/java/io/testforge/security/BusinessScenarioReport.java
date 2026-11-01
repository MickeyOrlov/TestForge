package io.testforge.security;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/** Source metadata only. Draft suites deliberately have empty expected statuses. */
public record BusinessScenarioReport(
        int formatVersion, List<Operation> operations, List<Link> links, List<Draft> drafts) {
    public BusinessScenarioReport {
        operations = List.copyOf(operations);
        links = List.copyOf(links);
        drafts = List.copyOf(drafts);
    }

    public record Operation(String key) {}

    public record Binding(String location, String parameter, String source, String pointer) {}

    public record Link(
            String id,
            String from,
            String to,
            String origin,
            String response,
            String status,
            String reason,
            List<Binding> bindings) {
        public Link {
            bindings = List.copyOf(bindings);
        }
    }

    public record Need(String step, String kind, String binding, String reason) {}

    public record Draft(
            String id, String status, List<String> chain, List<Need> needs, JsonNode suite) {
        public Draft {
            chain = List.copyOf(chain);
            needs = List.copyOf(needs);
        }

        @Override
        public String toString() {
            return "BusinessScenarioDraft[" + id + "," + status + "]";
        }
    }
}
