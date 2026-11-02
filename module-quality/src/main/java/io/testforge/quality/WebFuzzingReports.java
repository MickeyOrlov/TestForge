package io.testforge.quality;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Faults from <a href="https://github.com/WebFuzzing/Commons">Web Fuzzing Commons</a> reports — the
 * {@code report.json} EvoMaster writes, and any fuzzer that adopts the format. Exploring an API is the
 * fuzzer's job; this reads what it found.
 *
 * <p>One group per operation and fault category, named {@code <operation> <CATEGORY>}: the operation
 * as {@link OperationCatalog} names it, the category by its WFC name ({@code HTTP_STATUS_500},
 * {@code SCHEMA_INVALID_RESPONSE}, {@code SECURITY_XSS}), {@code WFC_<code>} for a code this table
 * does not know. Each group keeps the generated test that shows it, as evidence.
 */
final class WebFuzzingReports {

    /** WFC 0.7.0 {@code DefinedFaultCategory}: 1xx HTTP semantics, 2xx schema, 3xx security. */
    static final Map<Integer, String> CATEGORIES = Map.ofEntries(
            Map.entry(100, "HTTP_STATUS_500"), Map.entry(101, "HTTP_STATUS_NO_NON_STANDARD_CODES"),
            Map.entry(102, "HTTP_STATUS_NO_201_IF_DELETE"), Map.entry(103, "HTTP_STATUS_NO_201_IF_GET"),
            Map.entry(104, "HTTP_STATUS_NO_201_IF_PATCH"), Map.entry(105, "HTTP_STATUS_NO_204_IF_CONTENT"),
            Map.entry(106, "HTTP_STATUS_NO_413_IF_NO_PAYLOAD"), Map.entry(107, "HTTP_STATUS_NO_415_IF_NO_PAYLOAD"),
            Map.entry(108, "HTTP_STATUS_NO_304_IF_NO_GET_OR_HEAD"),
            Map.entry(109, "HTTP_STATUS_NO_401_IF_NO_WWW_AUTHENTICATE"),
            Map.entry(110, "HTTP_STATUS_NO_405_IF_NO_ALLOW"), Map.entry(111, "HTTP_STATUS_NO_205_IF_CONTENT"),
            Map.entry(112, "HTTP_STATUS_NO_426_IF_NO_UPGRADE"), Map.entry(113, "HTTP_NONWORKING_DELETE"),
            Map.entry(114, "HTTP_SIDE_EFFECTS_FAILED_MODIFICATION"), Map.entry(115, "HTTP_REPEATED_CREATE_PUT"),
            Map.entry(116, "HTTP_MISLEADING_CREATE_PUT"), Map.entry(117, "HTTP_PARTIAL_UPDATE_PUT"),
            Map.entry(118, "HTTP_NON_IDEMPOTENT_PUT"), Map.entry(119, "HTTP_INVALID_MERGE_PATCH"),
            Map.entry(120, "HTTP_INVALID_LOCATION"), Map.entry(200, "SCHEMA_INVALID_RESPONSE"),
            Map.entry(201, "SCHEMA_INVALID_ALLOW"), Map.entry(202, "SCHEMA_STATUS_NO_401_IF_NO_AUTH"),
            Map.entry(203, "SCHEMA_STATUS_NO_403_IF_NO_401"), Map.entry(204, "SCHEMA_STATUS_HAS_406_IF_ACCEPT"),
            Map.entry(205, "SCHEMA_STATUS_NO_501_IF_IMPLEMENTED"), Map.entry(206, "SCHEMA_VALIDATION_BYPASS"),
            Map.entry(300, "SECURITY_SQL_INJECTION"), Map.entry(301, "SECURITY_XSS"),
            Map.entry(302, "SECURITY_SSRF"), Map.entry(303, "SECURITY_MASS_ASSIGNMENT"),
            Map.entry(304, "SECURITY_EXISTENCE_LEAKAGE"), Map.entry(305, "SECURITY_NOT_RECOGNIZED_AUTHENTICATED"),
            Map.entry(306, "SECURITY_WRONG_AUTHORIZATION"), Map.entry(307, "SECURITY_IGNORE_ANONYMOUS"),
            Map.entry(308, "SECURITY_ANONYMOUS_MODIFICATIONS"), Map.entry(309, "SECURITY_LEAKED_STACK_TRACES"),
            Map.entry(310, "SECURITY_HIDDEN_ACCESSIBLE_ENDPOINT"));

    /**
     * @param groups   {@code <operation> <CATEGORY>} keys, one per group
     * @param facts    a summary per report, then each group with the test that shows it
     * @param unusable why the reports cannot be judged — missing, stale, not WFC; {@code null} when they can
     */
    record Read(List<String> groups, List<String> facts, String unusable) {
    }

    private WebFuzzingReports() {
    }

    static Read read(List<String> paths, Instant notBefore, OperationCatalog catalog, ObjectMapper mapper) {
        Map<String, String> evidence = new LinkedHashMap<>();
        List<String> summaries = new ArrayList<>();
        for (String location : paths) {
            Path file = Path.of(location);
            if (!Files.isRegularFile(file)) {
                return unusable("no WFC report at " + file + " (forge.quality.robustness.reports)");
            }
            String stale = InputFreshness.stale(file, notBefore);
            if (stale != null) {
                return unusable(stale);
            }
            JsonNode report;
            try {
                report = mapper.readTree(file.toFile());
            } catch (IOException e) {
                return unusable(file + " is not JSON");
            }
            if (!report.has("schemaVersion") || !report.path("faults").path("foundFaults").isArray()) {
                return unusable(file + " is not a WFC report (no schemaVersion / faults.foundFaults)");
            }
            String malformed = malformed(report.path("faults").path("foundFaults"));
            if (malformed != null) {
                return unusable(file + " has a malformed fault (" + malformed + "): its faults cannot be judged");
            }
            int[] families = new int[4];
            java.util.Set<String> operations = new java.util.HashSet<>();
            int before = evidence.size();
            for (JsonNode fault : report.path("faults").path("foundFaults")) {
                String operation = catalog.key(fault.path("operationId").asText());
                operations.add(operation);
                for (JsonNode category : fault.path("faultCategories")) {
                    int code = category.path("code").asInt();
                    families[Math.min(3, Math.max(0, code / 100))]++;
                    evidence.putIfAbsent(operation + " " + CATEGORIES.getOrDefault(code, "WFC_" + code),
                            fault.path("testCaseId").asText(""));
                }
            }
            summaries.add(report.path("toolName").asText("a fuzzer") + " " + report.path("toolVersion").asText("")
                    + " (WFC " + report.path("schemaVersion").asText() + "): " + (evidence.size() - before)
                    + " fault groups in " + operations.size() + " operations — " + families[1] + " HTTP, "
                    + families[2] + " schema, " + families[3] + " security findings (" + file + ")");
        }
        List<String> facts = new ArrayList<>(summaries);
        evidence.entrySet().stream().limit(QualityAssessor.LISTED).forEach(e -> facts.add("fault: " + e.getKey()
                + (e.getValue().isEmpty() ? "" : " → " + e.getValue())));
        if (evidence.size() > QualityAssessor.LISTED) {
            facts.add("… and " + (evidence.size() - QualityAssessor.LISTED) + " more fault groups");
        }
        return new Read(List.copyOf(evidence.keySet()), facts, null);
    }

    /**
     * Why a fault entry cannot be counted, or {@code null}: every fault needs an operation and at least
     * one category with an integer code — one without would read as a clean run.
     */
    private static String malformed(JsonNode faults) {
        int index = 0;
        for (JsonNode fault : faults) {
            if (!fault.path("operationId").isTextual() || fault.path("operationId").asText().isBlank()) {
                return "foundFaults[" + index + "] has no operationId";
            }
            JsonNode categories = fault.path("faultCategories");
            if (!categories.isArray() || categories.isEmpty()) {
                return "foundFaults[" + index + "] has no faultCategories";
            }
            for (JsonNode category : categories) {
                if (!category.path("code").isIntegralNumber()) {
                    return "foundFaults[" + index + "] has a category without an integer code";
                }
            }
            index++;
        }
        return null;
    }

    private static Read unusable(String why) {
        return new Read(List.of(), List.of(), why);
    }
}
