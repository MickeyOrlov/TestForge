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
 */
final class JunitResults {

    enum Outcome { PASSED, FAILED, SKIPPED }

    /** @param millis the last attempt's duration, from the report's {@code time} (seconds) */
    record TestCase(String className, String name, Outcome outcome, boolean flaky, String message, long millis) {

        String id() {
            return className + "." + name;
        }
    }

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

    private record Attempt(String className, String name, Outcome outcome, boolean flakyMarker, String message,
                           long millis) {
    }

    private static TestCase merge(List<Attempt> all) {
        Attempt first = all.getFirst();
        boolean passed = all.stream().anyMatch(a -> a.outcome() == Outcome.PASSED);
        boolean failed = all.stream().anyMatch(a -> a.outcome() == Outcome.FAILED);
        boolean marked = all.stream().anyMatch(Attempt::flakyMarker);
        Outcome outcome = passed ? Outcome.PASSED : failed ? Outcome.FAILED : Outcome.SKIPPED;
        String message = all.stream().filter(a -> a.outcome() == Outcome.FAILED || a.flakyMarker())
                .map(Attempt::message).filter(m -> m != null && !m.isBlank()).findFirst().orElse(null);
        return new TestCase(first.className(), first.name(), outcome, passed && (failed || marked), message,
                all.getLast().millis());
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
            for (Node child = testcase.getFirstChild(); child != null; child = child.getNextSibling()) {
                if (!(child instanceof Element element)) {
                    continue;
                }
                switch (element.getTagName()) {
                    case "failure", "error" -> {
                        outcome = Outcome.FAILED;
                        message = firstLine(element);
                    }
                    case "skipped" -> outcome = outcome == Outcome.FAILED ? outcome : Outcome.SKIPPED;
                    case "flakyFailure", "flakyError", "rerunFailure", "rerunError" -> {
                        flakyMarker = true;
                        message = message == null ? firstLine(element) : message;
                    }
                    default -> {
                    }
                }
            }
            attempts.computeIfAbsent(className + "." + name, ignored -> new ArrayList<>())
                    .add(new Attempt(className, name, outcome, flakyMarker, message, millis(testcase)));
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

    private static String firstLine(Element element) {
        String text = element.hasAttribute("message") ? element.getAttribute("message") : element.getTextContent();
        String line = text == null ? "" : text.strip().lines().findFirst().orElse("");
        return line.length() > 200 ? line.substring(0, 200) + "…" : line;
    }

    /** No DTDs, no external entities: a report is data, never a fetch. */
    private static DocumentBuilder parser() {
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
