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

    record TestCase(String className, String name, Outcome outcome, boolean flaky, String message) {

        String id() {
            return className + "." + name;
        }
    }

    private JunitResults() {
    }

    static List<TestCase> read(List<Path> roots) {
        Map<String, List<Attempt>> attempts = new LinkedHashMap<>();
        for (Path root : roots) {
            if (!Files.exists(root)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(root)) {
                for (Path file : files.filter(f -> f.getFileName().toString().endsWith(".xml")).sorted().toList()) {
                    read(file, attempts);
                }
            } catch (IOException e) {
                throw new QualityException("Could not read JUnit results under " + root, e);
            }
        }
        List<TestCase> tests = new ArrayList<>();
        attempts.forEach((id, all) -> tests.add(merge(all)));
        return tests;
    }

    private record Attempt(String className, String name, Outcome outcome, boolean flakyMarker, String message) {
    }

    private static TestCase merge(List<Attempt> all) {
        Attempt first = all.getFirst();
        boolean passed = all.stream().anyMatch(a -> a.outcome() == Outcome.PASSED);
        boolean failed = all.stream().anyMatch(a -> a.outcome() == Outcome.FAILED);
        boolean marked = all.stream().anyMatch(Attempt::flakyMarker);
        Outcome outcome = passed ? Outcome.PASSED : failed ? Outcome.FAILED : Outcome.SKIPPED;
        String message = all.stream().filter(a -> a.outcome() == Outcome.FAILED || a.flakyMarker())
                .map(Attempt::message).filter(m -> m != null && !m.isBlank()).findFirst().orElse(null);
        return new TestCase(first.className(), first.name(), outcome, passed && (failed || marked), message);
    }

    private static void read(Path file, Map<String, List<Attempt>> attempts) {
        Document document;
        try (InputStream in = Files.newInputStream(file)) {
            document = parser().parse(in);
        } catch (IOException | SAXException e) {
            // a file that is not a JUnit report (another tool's XML in the same tree) is not an error
            return;
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
                    .add(new Attempt(className, name, outcome, flakyMarker, message));
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
