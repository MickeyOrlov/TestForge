package io.testforge.quality;

import io.testforge.quality.QualityProperties.ComponentProperties;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Issues by the component that owns them: {@code forge.quality.components}
 * maps issue-key patterns — a test package, an operation path, a table — to a
 * component and its owner. The first component whose pattern matches wins;
 * what matches none is listed as {@value #UNASSIGNED}, so a gap in the
 * mapping shows instead of vanishing. A component per service gives each
 * service its own status.
 */
final class QualityComponents {

    static final String UNASSIGNED = "(unassigned)";

    private QualityComponents() {
    }

    static List<QualityReport.Component> assign(List<QualityDimension> dimensions,
                                                Map<String, ComponentProperties> components) {
        if (components.isEmpty()) {
            return List.of();
        }
        Map<String, List<Pattern>> patterns = new LinkedHashMap<>();
        components.forEach((name, component) -> patterns.put(name,
                component.match().stream().map(QualityComponents::glob).toList()));
        Map<String, Owned> owned = new LinkedHashMap<>();
        components.keySet().forEach(name -> owned.put(name, new Owned()));
        for (QualityDimension dimension : dimensions) {
            for (String issue : dimension.issues()) {
                String name = patterns.entrySet().stream()
                        .filter(e -> e.getValue().stream().anyMatch(p -> p.matcher(issue).matches()))
                        .map(Map.Entry::getKey).findFirst().orElse(UNASSIGNED);
                owned.computeIfAbsent(name, ignored -> new Owned()).add(dimension, issue);
            }
        }
        List<QualityReport.Component> result = new ArrayList<>();
        owned.forEach((name, issues) -> {
            ComponentProperties component = components.get(name);
            result.add(new QualityReport.Component(name, component == null ? null : component.owner(),
                    issues.status, issues.keys, issues.fresh));
        });
        return result;
    }

    /** {@code *} is any text, everything else literal. */
    static Pattern glob(String pattern) {
        return Pattern.compile(String.join(".*", Arrays.stream(pattern.split("\\*", -1)).map(Pattern::quote)
                .toList()), Pattern.DOTALL);
    }

    private static final class Owned {

        private final List<String> keys = new ArrayList<>();
        private QualityStatus status = QualityStatus.PASS;
        private int fresh;

        void add(QualityDimension dimension, String issue) {
            keys.add(dimension.name() + " " + issue);
            if (dimension.newIssues().contains(issue)) {
                fresh++;
            }
            if (dimension.status().worseThan(status)) {
                status = dimension.status();
            }
        }
    }
}
