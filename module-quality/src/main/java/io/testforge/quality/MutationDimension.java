package io.testforge.quality;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * How strong the tests are, from what PIT already measured: its {@code mutations.xml}. PIT runs
 * in the project's own build; this only reads the report — a mutation engine is not repeated here.
 *
 * <p>Detected means KILLED, TIMED_OUT, MEMORY_ERROR or RUN_ERROR (PIT's own reading); NON_VIABLE
 * mutants never compiled and are left out. Issues are surviving mutants by method and mutator —
 * without the line, which moves with every edit above it.
 */
final class MutationDimension {

    static final String REPORT = "mutations.xml";
    private static final Set<String> DETECTED = Set.of("KILLED", "TIMED_OUT", "MEMORY_ERROR", "RUN_ERROR");

    private MutationDimension() {
    }

    static QualityDimension of(QualityProperties.MutationProperties properties) {
        List<Path> reports = new ArrayList<>();
        for (String root : properties.reports()) {
            Path path = Path.of(root);
            if (Files.isRegularFile(path)) {
                reports.add(path);
            } else if (Files.isDirectory(path)) {
                try (Stream<Path> files = Files.walk(path)) {
                    files.filter(f -> f.getFileName().toString().equals(REPORT)).sorted().forEach(reports::add);
                } catch (IOException e) {
                    throw new QualityException("Could not read PIT reports under " + path, e);
                }
            }
        }
        if (reports.isEmpty()) {
            return QualityDimension.notMeasured(QualityDimension.MUTATION, "no PIT " + REPORT + " under "
                    + properties.reports() + " (run pitest with the XML output format)");
        }
        int detected = 0;
        int survived = 0;
        int uncovered = 0;
        Set<String> issues = new LinkedHashSet<>();
        List<String> survivors = new ArrayList<>();
        for (Path report : reports) {
            NodeList mutations = parse(report).getElementsByTagName("mutation");
            for (int i = 0; i < mutations.getLength(); i++) {
                Element mutation = (Element) mutations.item(i);
                String status = mutation.getAttribute("status");
                if (DETECTED.contains(status)) {
                    detected++;
                } else if ("SURVIVED".equals(status)) {
                    survived++;
                    String method = text(mutation, "mutatedClass") + "." + text(mutation, "mutatedMethod");
                    String mutator = text(mutation, "mutator");
                    issues.add(method + " " + mutator.substring(mutator.lastIndexOf('.') + 1));
                    survivors.add(method + " line " + text(mutation, "lineNumber") + " — "
                            + text(mutation, "description"));
                } else if ("NO_COVERAGE".equals(status)) {
                    uncovered++;
                }
            }
        }
        int total = detected + survived + uncovered;
        if (total == 0) {
            return QualityDimension.notMeasured(QualityDimension.MUTATION, "no mutation in " + reports);
        }
        double score = (double) detected / total;
        List<String> facts = new ArrayList<>();
        facts.add(total + " mutants: " + detected + " detected, " + survived + " survived, " + uncovered
                + " not covered by any test (test strength " + (detected + survived == 0 ? "—"
                : percent((double) detected / (detected + survived))) + " on covered code)");
        survivors.stream().limit(QualityAssessor.LISTED).forEach(s -> facts.add("survived: " + s));
        if (survivors.size() > QualityAssessor.LISTED) {
            facts.add("… and " + (survivors.size() - QualityAssessor.LISTED) + " more survived");
        }
        QualityStatus status = survived + uncovered == 0 ? QualityStatus.PASS
                : properties.minScore() != null && score < properties.minScore() ? QualityStatus.FAIL
                : QualityStatus.WARN;
        if (properties.minScore() != null) {
            facts.add("minimum " + percent(properties.minScore()) + " (forge.quality.mutation.min-score)");
        }
        return new QualityDimension(QualityDimension.MUTATION, status, (int) Math.round(100 * score),
                "100 × detected / (detected + survived + not covered)", facts).withIssues(List.copyOf(issues));
    }

    private static Document parse(Path report) {
        try (InputStream in = Files.newInputStream(report)) {
            return JunitResults.parser().parse(in);
        } catch (IOException | SAXException e) {
            throw new QualityException("Could not read the PIT report " + report, e);
        }
    }

    private static String text(Element mutation, String tag) {
        NodeList nodes = mutation.getElementsByTagName(tag);
        return nodes.getLength() == 0 ? "" : nodes.item(0).getTextContent().strip();
    }

    private static String percent(double share) {
        return String.format(Locale.ROOT, "%.1f%%", 100 * share);
    }
}
