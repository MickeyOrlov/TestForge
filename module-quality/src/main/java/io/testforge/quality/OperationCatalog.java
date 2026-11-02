package io.testforge.quality;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * One name per API operation across every report: {@code <spec> <METHOD> <documented template>},
 * the form {@code api-coverage.json} lists. Producers name an operation each their own way — the
 * traffic summary by its URL path ({@code GET /petclinic/api/owners/{n}}), a WFC fuzzer by
 * {@code GET:/petclinic/api/owners/{ownerId}} — and the same defect would otherwise be three issues no
 * component pattern or owner can bring together.
 *
 * <p>A raw path matches a template when the template's segments end it ({@code {x}} any one
 * segment), so a base path the document's servers add ({@code /petclinic/api}) needs no
 * configuration. Of several matches the one with more segments wins, then the one with more literal
 * segments: {@code /v2/owners} over {@code /owners}. What matches nothing keeps its own name.
 */
final class OperationCatalog {

    private record Operation(String key, String method, Pattern path, int segments, int literals) {
    }

    private final List<Operation> operations;

    private OperationCatalog(List<Operation> operations) {
        this.operations = operations;
    }

    static OperationCatalog empty() {
        return new OperationCatalog(List.of());
    }

    /** @param keys documented operations as {@code <spec> <METHOD> <template>} */
    static OperationCatalog of(Collection<String> keys) {
        List<Operation> operations = new ArrayList<>();
        for (String key : keys) {
            String[] parts = key.split(" ", 3);
            if (parts.length < 3 || !parts[2].startsWith("/")) {
                continue;
            }
            String[] segments = parts[2].substring(1).split("/", -1);
            StringBuilder regex = new StringBuilder("^(?:/.*)?");
            int literals = 0;
            for (String segment : segments) {
                regex.append('/');
                if (segment.startsWith("{") && segment.endsWith("}")) {
                    regex.append("[^/]+");
                } else {
                    regex.append(Pattern.quote(segment));
                    literals++;
                }
            }
            operations.add(new Operation(key, parts[1].toUpperCase(java.util.Locale.ROOT),
                    Pattern.compile(regex.append("/?$").toString()), segments.length, literals));
        }
        return new OperationCatalog(List.copyOf(operations));
    }

    boolean isEmpty() {
        return operations.isEmpty();
    }

    /**
     * The documented name of {@code operation} — {@code "GET /a/b"} or {@code "GET:/a/b"}, a query
     * ignored — or {@code operation} itself when the catalog does not know it.
     */
    String key(String operation) {
        String trimmed = operation.strip();
        int split = trimmed.indexOf(' ');
        int colon = trimmed.indexOf(":/");
        int at = split > 0 && (colon < 0 || split < colon) ? split : colon;
        if (at <= 0) {
            return operation;
        }
        String method = trimmed.substring(0, at).toUpperCase(java.util.Locale.ROOT);
        String path = trimmed.substring(at + 1).strip().split("[?#]", 2)[0];
        return operations.stream()
                .filter(o -> o.method().equals(method) && o.path().matcher(path).matches())
                .max(Comparator.comparingInt(Operation::segments).thenComparingInt(Operation::literals))
                .map(Operation::key)
                .orElse(operation);
    }
}
