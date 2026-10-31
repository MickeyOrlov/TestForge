package io.testforge.api.explorer;

import io.testforge.api.discovery.ApiDiscoveryProperties;
import io.testforge.api.discovery.ApiSpecSource;
import io.testforge.api.discovery.OpenApiSpecParser;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The document-side half of the contract guard: finds the operation a real
 * request hit and checks the response with the same {@link ResponseContractChecker}
 * the explorer uses. Thread-safe; the documents are parsed once, on first use.
 */
public class ContractGuard {

    private static final Logger log = LoggerFactory.getLogger(ContractGuard.class);

    /** Pseudo-kind for a call the document does not describe at all. */
    static final String UNDOCUMENTED_OPERATION = "UNDOCUMENTED_OPERATION";

    private final OpenApiSpecParser parser;
    private final OperationSelector selector;
    private final ResponseContractChecker checker;
    private final ApiDiscoveryProperties discovery;
    private final ContractGuardProperties properties;
    private final Map<String, Finding> findings = new ConcurrentHashMap<>();
    private final AtomicLong checked = new AtomicLong();
    private volatile List<Route> routes;

    /** One distinct problem and how often the suite ran into it. */
    public record Finding(String operation, String target, Integer status, String kind, String location,
                          String detail, long count) {
    }

    private record Route(String method, Pattern path, int variables, ExplorableOperation operation) {
    }

    public ContractGuard(OpenApiSpecParser parser, OperationSelector selector, ResponseContractChecker checker,
                         ApiDiscoveryProperties discovery, ContractGuardProperties properties) {
        this.parser = parser;
        this.selector = selector;
        this.checker = checker;
        this.discovery = discovery;
        this.properties = properties;
    }

    /**
     * Checks one exchange and returns its problems (empty when it conforms).
     *
     * @param method HTTP method as sent
     * @param path   request path relative to the API base URL, without query
     */
    public List<Finding> check(String method, String path, RuntimeExchange exchange) {
        checked.incrementAndGet();
        Optional<ExplorableOperation> operation = find(method, path);
        String target = method + " " + path;
        List<Finding> problems = new ArrayList<>();
        if (operation.isEmpty()) {
            problems.add(record(target, target, exchange.status(), UNDOCUMENTED_OPERATION, "$",
                    "the document has no operation for this method and path"));
            return problems;
        }
        for (ContractMismatch mismatch : checker.check(operation.get(), exchange)) {
            problems.add(record(operation.get().key(), target, exchange.status(), mismatch.kind().name(),
                    mismatch.location(), mismatch.detail()));
        }
        return problems;
    }

    /** Every distinct finding so far, most frequent first, then by operation. */
    public List<Finding> findings() {
        return findings.values().stream()
                .sorted(Comparator.comparingLong(Finding::count).reversed()
                        .thenComparing(Finding::operation).thenComparing(Finding::kind))
                .toList();
    }

    public long checkedResponses() {
        return checked.get();
    }

    Optional<ExplorableOperation> find(String method, String path) {
        return routes().stream()
                .filter(route -> route.method().equalsIgnoreCase(method) && route.path().matcher(path).matches())
                // "/tasks/active" before "/tasks/{id}"
                .min(Comparator.comparingInt(Route::variables))
                .map(Route::operation);
    }

    private Finding record(String operation, String target, Integer status, String kind, String location,
                           String detail) {
        String key = operation + "|" + kind + "|" + location + "|" + detail;
        Finding updated = findings.merge(key, new Finding(operation, target, status, kind, location, detail, 1),
                (previous, next) -> new Finding(previous.operation(), previous.target(), previous.status(),
                        kind, location, detail, previous.count() + 1));
        if (updated.count() == 1) {
            log.warn("Contract guard: {} {} at {} — {} (first seen on {})", operation, kind, location, detail, target);
        }
        return updated;
    }

    private List<Route> routes() {
        List<Route> current = routes;
        if (current == null) {
            synchronized (this) {
                if (routes == null) {
                    routes = load();
                }
                current = routes;
            }
        }
        return current;
    }

    private List<Route> load() {
        List<Route> loaded = new ArrayList<>();
        Map<String, ApiDiscoveryProperties.Spec> specs = new LinkedHashMap<>(new java.util.TreeMap<>(discovery.specs()));
        specs.forEach((id, spec) -> {
            if (!properties.specs().isEmpty() && !properties.specs().contains(id)) {
                return;
            }
            for (ExplorableOperation operation : selector.select(id, parser.parse(new ApiSpecSource(id, spec.location())))) {
                loaded.add(new Route(operation.method(), pattern(operation.pathTemplate()),
                        variables(operation.pathTemplate()), operation));
            }
        });
        if (loaded.isEmpty()) {
            log.warn("Contract guard is enabled but no operation was loaded from forge.api-discovery.specs");
        }
        return List.copyOf(loaded);
    }

    /** {@code /tasks/{taskId}} → {@code ^/tasks/[^/]+$}, everything else literal. */
    static Pattern pattern(String template) {
        StringBuilder regex = new StringBuilder("^");
        int cursor = 0;
        while (cursor < template.length()) {
            int open = template.indexOf('{', cursor);
            if (open < 0) {
                regex.append(Pattern.quote(template.substring(cursor)));
                break;
            }
            int close = template.indexOf('}', open);
            if (close < 0) {
                regex.append(Pattern.quote(template.substring(cursor)));
                break;
            }
            if (open > cursor) {
                regex.append(Pattern.quote(template.substring(cursor, open)));
            }
            regex.append("[^/]+");
            cursor = close + 1;
        }
        return Pattern.compile(regex.append("/?$").toString());
    }

    private static int variables(String template) {
        return (int) template.chars().filter(c -> c == '{').count();
    }
}
