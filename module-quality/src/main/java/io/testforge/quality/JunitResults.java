package io.testforge.quality;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * Test outcomes from JUnit XML reports (Gradle, Surefire, Failsafe), one entry
 * per test however many times it ran.
 *
 * <p>A test is <b>flaky</b> when it failed and passed within the same run: two
 * {@code <testcase>} entries of one name (Gradle's test-retry), or a
 * {@code <flakyFailure>} / {@code <flakyError>} element (Surefire's rerun).
 * Its final outcome is a pass — the stability dimension counts it, the
 * functional one does not.
 *
 * <p>A failure is an <b>assertion</b> when a check in the test failed
 * ({@code AssertionError}, opentest4j, AssertJ) and an <b>error</b> when
 * anything else was thrown — usually the test or its environment. Its
 * <b>fingerprint</b> is a short hash of the exception type and the first
 * frames of the stack trace outside test libraries, without line numbers:
 * the same failure keeps it across runs and edits elsewhere in the file.
 * Neither the trace nor its hash input goes into a report.
 */
final class JunitResults {

    enum Outcome { PASSED, FAILED, SKIPPED }

    /**
     * @param millis      the last attempt's duration, from the report's {@code time} (seconds)
     * @param error       the failure was not an assertion: the test or its environment threw
     * @param fingerprint the failure's {@link #fingerprint(String, String)}; {@code null} without a type or trace.
     *                    Attempts that failed differently (retries) join theirs with {@code ,}, so no single
     *                    quarantined fingerprint matches them
     */
    record TestCase(String className, String name, Outcome outcome, boolean flaky, String message, long millis,
                    boolean error, String fingerprint) {

        TestCase(String className, String name, Outcome outcome, boolean flaky, String message, long millis) {
            this(className, name, outcome, flaky, message, millis, false, null);
        }

        String id() {
            return className + "." + name;
        }
    }

    /** Frames from these packages say how a check failed, not where: the fingerprint skips them. */
    private static final List<String> LIBRARY_FRAMES = List.of("java.", "javax.", "jdk.", "sun.", "org.junit.",
            "junit.", "org.opentest4j.", "org.assertj.", "org.hamcrest.", "org.gradle.", "org.apache.maven.",
            "worker.org.gradle.");
    private static final int FINGERPRINT_FRAMES = 3;
    private static final java.util.regex.Pattern FRAME =
            java.util.regex.Pattern.compile("^\\s*at\\s+([\\w$.<>/]+?)\\s*\\(");

    private JunitResults() {
    }

    /** What was read, and the report files left out as older than {@code notBefore}. */
    record Read(List<TestCase> tests, List<String> stale) {
    }

    static List<TestCase> read(List<Path> roots) {
        return read(roots, null).tests();
    }

    /**
     * @param notBefore suites whose {@code timestamp} is earlier are stale — results of a task that
     *                  did not run in this build — and are skipped; {@code null} keeps everything
     */
    static Read read(List<Path> roots, java.time.Instant notBefore) {
        Map<String, List<Attempt>> attempts = new LinkedHashMap<>();
        List<String> stale = new ArrayList<>();
        for (Path root : roots) {
            if (!Files.exists(root)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(root)) {
                for (Path file : files.filter(f -> f.getFileName().toString().endsWith(".xml")).sorted().toList()) {
                    if (!read(file, attempts, notBefore)) {
                        stale.add(file.toString());
                    }
                }
            } catch (IOException e) {
                throw new QualityException("Could not read JUnit results under " + root, e);
            }
        }
        List<TestCase> tests = new ArrayList<>();
        attempts.forEach((id, all) -> tests.add(merge(all)));
        return new Read(tests, List.copyOf(stale));
    }

    /** @param fingerprints one per failed attempt in the element, {@code "none"} where it had none */
    private record Attempt(String className, String name, Outcome outcome, boolean flakyMarker, String message,
                           long millis, boolean error, List<String> fingerprints) {
    }

    private static TestCase merge(List<Attempt> all) {
        Attempt first = all.getFirst();
        boolean passed = all.stream().anyMatch(a -> a.outcome() == Outcome.PASSED);
        boolean failed = all.stream().anyMatch(a -> a.outcome() == Outcome.FAILED);
        boolean marked = all.stream().anyMatch(Attempt::flakyMarker);
        Outcome outcome = passed ? Outcome.PASSED : failed ? Outcome.FAILED : Outcome.SKIPPED;
        String message = all.stream().filter(a -> a.outcome() == Outcome.FAILED || a.flakyMarker())
                .map(Attempt::message).filter(m -> m != null && !m.isBlank()).findFirst().orElse(null);
        List<Attempt> failing = all.stream().filter(a -> a.outcome() == Outcome.FAILED || a.flakyMarker()).toList();
        List<String> fingerprints = failing.stream().flatMap(a -> a.fingerprints().stream()).distinct().toList();
        String fingerprint = fingerprints.isEmpty() || fingerprints.equals(List.of("none")) ? null
                : String.join(",", fingerprints);
        return new TestCase(first.className(), first.name(), outcome, passed && (failed || marked), message,
                all.getLast().millis(), failing.stream().anyMatch(Attempt::error), fingerprint);
    }

    /** @return false when the file's suite is older than {@code notBefore} and was skipped */
    private static boolean read(Path file, Map<String, List<Attempt>> attempts, java.time.Instant notBefore) {
        Document document;
        try (InputStream in = Files.newInputStream(file)) {
            document = parser().parse(in);
        } catch (IOException | SAXException e) {
            // a file that is not a JUnit report (another tool's XML in the same tree) is not an error
            return true;
        }
        if (notBefore != null) {
            java.time.Instant written = timestamp(document.getDocumentElement().getAttribute("timestamp"));
            if (written != null && written.isBefore(notBefore)) {
                return false;
            }
        }
        NodeList cases = document.getElementsByTagName("testcase");
        for (int i = 0; i < cases.getLength(); i++) {
            Element testcase = (Element) cases.item(i);
            String className = testcase.getAttribute("classname");
            String name = testcase.getAttribute("name");
            Outcome outcome = Outcome.PASSED;
            boolean flakyMarker = false;
            String message = null;
            boolean error = false;
            // every failed attempt in this element: Surefire's reruns sit inside one <testcase>
            List<String> fingerprints = new ArrayList<>();
            for (Node child = testcase.getFirstChild(); child != null; child = child.getNextSibling()) {
                if (!(child instanceof Element element)) {
                    continue;
                }
                switch (element.getTagName()) {
                    case "failure", "error" -> {
                        outcome = Outcome.FAILED;
                        message = firstLine(element);
                        String type = type(element);
                        error |= "error".equals(element.getTagName()) || type != null && !assertion(type);
                        fingerprints.add(attemptFingerprint(type, element));
                    }
                    case "skipped" -> outcome = outcome == Outcome.FAILED ? outcome : Outcome.SKIPPED;
                    case "flakyFailure", "flakyError", "rerunFailure", "rerunError" -> {
                        flakyMarker = true;
                        message = message == null ? firstLine(element) : message;
                        String type = type(element);
                        error |= element.getTagName().endsWith("Error") || type != null && !assertion(type);
                        fingerprints.add(attemptFingerprint(type, element));
                    }
                    default -> {
                    }
                }
            }
            attempts.computeIfAbsent(className + "." + name, ignored -> new ArrayList<>())
                    .add(new Attempt(className, name, outcome, flakyMarker, message, millis(testcase), error,
                            fingerprints));
        }
        return true;
    }

    /** Gradle writes {@code 2026-11-01T16:55:22.305Z}; Surefire a local time without a zone. */
    static java.time.Instant timestamp(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return java.time.OffsetDateTime.parse(value).toInstant();
        } catch (java.time.format.DateTimeParseException e) {
            try {
                return java.time.LocalDateTime.parse(value).atZone(java.time.ZoneId.systemDefault()).toInstant();
            } catch (java.time.format.DateTimeParseException ignored) {
                return null;
            }
        }
    }

    private static long millis(Element testcase) {
        try {
            return Math.round(Double.parseDouble(testcase.getAttribute("time").replace(",", "")) * 1000);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** The exception class: the {@code type} attribute, or the trace's first line before its colon. */
    private static String type(Element element) {
        if (element.hasAttribute("type") && !element.getAttribute("type").isBlank()) {
            return element.getAttribute("type").strip();
        }
        String first = element.getTextContent() == null ? "" : element.getTextContent().strip().lines().findFirst()
                .orElse("");
        String candidate = first.split(":", 2)[0].strip();
        return candidate.matches("[\\w$]+(\\.[\\w$]+)+") ? candidate : null;
    }

    private static String attemptFingerprint(String type, Element element) {
        String fingerprint = fingerprint(type, element.getTextContent());
        return fingerprint == null ? "none" : fingerprint;
    }

    /** A failed check rather than a thrown exception. */
    static boolean assertion(String type) {
        String simple = type.substring(type.lastIndexOf('.') + 1);
        return simple.endsWith("AssertionError") || simple.endsWith("AssertionFailedError")
                || simple.equals("ComparisonFailure") || simple.equals("MultipleFailuresError")
                || type.startsWith("org.opentest4j.") || type.startsWith("org.assertj.");
    }

    /**
     * 12 hex digits of SHA-256 over the exception type and the first {@value #FINGERPRINT_FRAMES}
     * frames outside test libraries — class and method, no line number, lambda and proxy counters
     * dropped. {@code null} when there is neither a type nor a frame.
     */
    static String fingerprint(String type, String trace) {
        List<String> frames = new ArrayList<>();
        for (String line : (trace == null ? "" : trace).lines().toList()) {
            java.util.regex.Matcher frame = FRAME.matcher(line);
            if (!frame.find()) {
                continue;
            }
            // "java.base/java.lang.Thread.run", "app//io.acme.T.m": the module prefix is not the method
            String method = frame.group(1).replaceFirst("^[\\w.@-]*//?", "")
                    .replaceAll("\\$\\$Lambda[$\\w/]*", "\\$\\$Lambda")
                    .replaceAll("(lambda\\$\\w+?)\\$\\d+", "$1")
                    .replaceAll("\\$\\d+", "\\$");
            if (LIBRARY_FRAMES.stream().noneMatch(method::startsWith)) {
                frames.add(method);
            }
            if (frames.size() == FINGERPRINT_FRAMES) {
                break;
            }
        }
        if ((type == null || type.isBlank()) && frames.isEmpty()) {
            return null;
        }
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(
                    ((type == null ? "" : type) + "\n" + String.join("\n", frames))
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest).substring(0, 12);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every JDK", e);
        }
    }

    private static String firstLine(Element element) {
        String text = element.hasAttribute("message") ? element.getAttribute("message") : element.getTextContent();
        String line = text == null ? "" : text.strip().lines().findFirst().orElse("");
        return line.length() > 200 ? line.substring(0, 200) + "…" : line;
    }

    /** No DTDs, no external entities: a report is data, never a fetch. */
    static DocumentBuilder parser() {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            return factory.newDocumentBuilder();
        } catch (ParserConfigurationException e) {
            throw new QualityException("No secure XML parser available", e);
        }
    }
}
