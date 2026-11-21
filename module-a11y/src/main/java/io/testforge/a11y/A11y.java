package io.testforge.a11y;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Page;
import io.testforge.artifact.ArtifactSink;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Pattern;

/** Offline baseline/policy over the native axe findings. Only check/analyze touches a browser. */
public final class A11y {
    public record Baseline(int formatVersion, String policySha256, Map<String, String> impacts) { }
    private final A11yProperties properties;
    private final A11yEngine engine;
    private final ArtifactSink sink;
    private final Clock clock;
    private final ObjectMapper mapper = new ObjectMapper();
    public A11y(A11yProperties properties, A11yEngine engine, ArtifactSink sink) {
        this(properties, engine, sink, Clock.systemUTC());
    }
    A11y(A11yProperties properties, A11yEngine engine, ArtifactSink sink, Clock clock) {
        this.properties = properties; this.engine = engine; this.sink = sink == null ? ArtifactSink.NO_OP : sink; this.clock = clock;
    }
    public A11yResult check(Page page) { return check(page, pageKey(page), List.of(), List.of()); }
    public A11yResult check(Page page, List<String> include, List<String> exclude) {
        return check(page, pageKey(page), include, exclude);
    }
    public A11yResult check(Page page, String pageKey) { return check(page, pageKey, List.of(), List.of()); }
    public A11yResult check(Page page, String pageKey, List<String> include, List<String> exclude) {
        if (!properties.enabled()) throw new IllegalStateException("Set forge.a11y.enabled=true");
        A11yProperties.validatePageKey(pageKey);
        include = selectors(include); exclude = selectors(exclude);
        String policy = policy(include, exclude);
        Baseline baseline = baseline(policy);
        A11yEngine.Scan scan = engine.analyze(page, properties, include, exclude);
        if (scan == null || scan.violations() == null || scan.incomplete() == null || scan.engineVersion() == null)
            throw new IllegalStateException("No usable axe scan");
        List<A11yResult.Finding> violations = findings(scan.violations(), pageKey, baseline, false);
        List<A11yResult.Finding> incomplete = findings(scan.incomplete(), pageKey, baseline, true);
        var fresh = violations.stream().filter(v -> v.disposition().equals("NEW")
                && severity(v.impact()) >= severity(properties.minimumImpact())).map(A11yResult.Finding::key).toList();
        String id = UUID.randomUUID().toString();
        var result = new A11yResult(1, id, pageKey, Instant.now(clock).toString(), scan.engineVersion(), policy,
                violations, incomplete, fresh, properties.disabledRules(), Path.of(properties.outputDir(), id).toString());
        publish(result);
        return result;
    }
    public A11yResult assertNoNewViolations(Page page) { return requirePassed(check(page)); }
    public A11yResult assertNoNewViolations(Page page, String pageKey) { return requirePassed(check(page, pageKey)); }
    private A11yResult requirePassed(A11yResult result) {
        if (!result.passed()) throw new AssertionError("Accessibility check: " + result.newBlockingKeys().size()
                + " new blocking violations, " + result.incomplete().size() + " nodes need manual review. See " + result.directory());
        return result;
    }
    private List<String> selectors(List<String> selectors) {
        if (selectors == null || selectors.stream().anyMatch(s -> s == null || s.isBlank()))
            throw new IllegalArgumentException("Use explicit nonempty selectors or an empty list");
        return List.copyOf(selectors);
    }
    private String pageKey(Page page) {
        if (!properties.enabled()) throw new IllegalStateException("Set forge.a11y.enabled=true");
        String path;
        try { path = URI.create(page.url()).getPath(); }
        catch (RuntimeException e) { throw new IllegalArgumentException("Supply an explicit page path template"); }
        List<String> matches = properties.pageTemplates().stream().filter(template -> {
            String regex = Arrays.stream(template.split("/", -1))
                    .map(segment -> segment.matches("\\{[A-Za-z][A-Za-z0-9_]*\\}") ? "[^/]+" : Pattern.quote(segment))
                    .collect(java.util.stream.Collectors.joining("/"));
            return path != null && path.matches(regex);
        }).toList();
        if (matches.size() != 1) throw new IllegalArgumentException("Supply one unambiguous page path template");
        return matches.getFirst();
    }
    static String hash(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
    private String policy(List<String> include, List<String> exclude) {
        try {
            return hash(mapper.writeValueAsString(List.of("axe-java-4.11.3", new TreeSet<>(properties.tags()),
                    new TreeMap<>(properties.disabledRules()), properties.minimumImpact(), new TreeSet<>(include),
                    new TreeSet<>(exclude), new TreeMap<>(properties.selectorAliases()))));
        } catch (IOException e) { throw new IllegalStateException("Could not identify scan policy"); }
    }
    private Baseline baseline(String policy) {
        if (properties.baselineFile() == null || !Files.exists(Path.of(properties.baselineFile()))) return new Baseline(1, policy, Map.of());
        try {
            var baseline = mapper.readValue(Path.of(properties.baselineFile()).toFile(), Baseline.class);
            if (baseline.formatVersion() != 1 || !policy.equals(baseline.policySha256()) || baseline.impacts() == null
                    || baseline.impacts().entrySet().stream().anyMatch(e -> e.getKey().isBlank() || severity(e.getValue()) == 5))
                throw new IOException();
            return baseline;
        } catch (IOException | RuntimeException e) { throw new IllegalStateException("Invalid or incompatible a11y baseline; review and promote explicitly"); }
    }
    private List<A11yResult.Finding> findings(List<A11yEngine.Node> nodes, String page, Baseline baseline, boolean incomplete) {
        Map<String, A11yResult.Finding> unique = new LinkedHashMap<>();
        for (var node : nodes) {
            String selector = node.selector().strip();
            String normalized = properties.selectorAliases().getOrDefault(selector, selector);
            String key = page + " " + node.rule() + " " + hash(normalized);
            String disposition = "NEW";
            if (baseline.impacts().containsKey(key) && severity(node.impact()) <= severity(baseline.impacts().get(key))) disposition = "BASELINE";
            for (var acceptance : properties.accepted()) if (acceptance.key().equals(key)) {
                // An explicit deadline is narrower than an older baseline decision.
                disposition = LocalDate.now(clock).isAfter(acceptance.until()) ? "NEW" : "ACCEPTED";
            }
            if (incomplete) disposition = "MANUAL_REVIEW";
            String safe = normalized.replaceAll("(['\"])[^'\"]*['\"]", "<redacted>")
                    .replaceAll("[0-9a-fA-F]{8}-[0-9a-fA-F-]{27,}", "<id>");
            String help = node.helpUrl() != null && node.helpUrl().matches("https://dequeuniversity[.]com/rules/[A-Za-z0-9_./?#=-]+")
                    ? node.helpUrl() : null;
            var finding = new A11yResult.Finding(key, node.rule(), node.impact() == null ? "unknown" : node.impact(),
                    List.copyOf(node.tags()), safe, help, disposition);
            var before = unique.get(key);
            if (before == null || severity(finding.impact()) > severity(before.impact())) unique.put(key, finding);
        }
        return List.copyOf(unique.values());
    }
    static int severity(String impact) {
        if (impact == null) return 5;
        return switch (impact) { case "minor" -> 1; case "moderate" -> 2; case "serious" -> 3; case "critical" -> 4; default -> 5; };
    }
    /** Explicit promotion only; no baseline mutation during scans or assertions. */
    public void writeBaseline(A11yResult result) {
        if (!properties.enabled() || properties.baselineFile() == null) throw new IllegalStateException("Enable a11y and configure baseline-file");
        if (result == null || result.formatVersion() != 1 || !result.incomplete().isEmpty())
            throw new IllegalArgumentException("Only completed scans without manual-review items can become a baseline");
        if (result.violations().stream().anyMatch(v -> severity(v.impact()) == 5))
            throw new IllegalArgumentException("Unknown native impact cannot become a baseline");
        Map<String, String> impacts = new TreeMap<>(baseline(result.policySha256()).impacts());
        // Promoting one page never erases adoption decisions for the other pages.
        impacts.keySet().removeIf(key -> key.startsWith(result.pageKey() + " "));
        result.violations().forEach(v -> impacts.put(v.key(), v.impact()));
        Path target = Path.of(properties.baselineFile()).toAbsolutePath();
        Path temporary = null;
        try {
            Files.createDirectories(target.getParent());
            temporary = Files.createTempFile(target.getParent(), "a11y-baseline-", ".json");
            mapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), new Baseline(1, result.policySha256(), impacts));
            try { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (java.nio.file.AtomicMoveNotSupportedException e) { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING); }
        } catch (IOException e) { throw new IllegalStateException("Could not promote a11y baseline"); }
        finally { if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException ignored) { /* best effort */ } }
    }
    private void publish(A11yResult result) {
        try {
            Path directory = Path.of(result.directory()); Files.createDirectories(directory);
            Path json = directory.resolve("a11y.json"), md = directory.resolve("a11y.md");
            mapper.writerWithDefaultPrettyPrinter().writeValue(json.toFile(), result);
            StringBuilder text = new StringBuilder("# Automated accessibility check\n\nPage: " + result.pageKey()
                    + "\nNew blocking violations: " + result.newBlockingKeys().size() + "\nManual review nodes: " + result.incomplete().size() + "\n\n");
            for (var finding : result.violations()) text.append("- ").append(finding.rule()).append(" · ").append(finding.impact())
                    .append(" · ").append(finding.disposition()).append(" · ").append(finding.selector().replace("\n", " "))
                    .append(" · ").append(finding.helpUrl()).append('\n');
            Files.writeString(md, text.toString());
            register(json, "a11y.json", "application/json");
            register(md, "a11y.md", "text/markdown");
        } catch (IOException | RuntimeException ignored) { /* Diagnostics never change the native scan verdict. */ }
    }

    private void register(Path file, String name, String mediaType) throws IOException {
        if (sink == ArtifactSink.NO_OP) return;
        Path indexed = sink.newFile("module-a11y", name);
        Files.copy(file, indexed, StandardCopyOption.REPLACE_EXISTING);
        sink.registerFile("module-a11y", "a11y", name, indexed, mediaType, Map.of());
    }
}
