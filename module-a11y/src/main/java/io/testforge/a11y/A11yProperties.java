package io.testforge.a11y;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("forge.a11y")
public record A11yProperties(boolean enabled, List<String> tags, Map<String, String> disabledRules,
        String minimumImpact, String baselineFile, String outputDir, List<String> pageTemplates,
        List<Accepted> accepted, Map<String, String> selectorAliases) {
    public record Accepted(String key, String reason, LocalDate until) {
        public Accepted {
            if (key == null || key.isBlank() || reason == null || reason.isBlank() || until == null)
                throw new IllegalArgumentException("Accepted a11y entries need key, reason and expiry");
        }
    }
    public A11yProperties {
        tags = List.copyOf(tags == null ? List.of("wcag2a", "wcag2aa") : tags);
        if (tags.isEmpty() || tags.stream().anyMatch(t -> !t.matches("[a-z0-9-]+")))
            throw new IllegalArgumentException("Use explicit axe tags");
        disabledRules = Map.copyOf(disabledRules == null ? Map.of() : disabledRules);
        disabledRules.forEach((rule, reason) -> {
            if (!rule.matches("[a-z0-9-]+") || reason.isBlank()) throw new IllegalArgumentException("Disabled rules need a reason");
        });
        minimumImpact = minimumImpact == null ? "serious" : minimumImpact;
        if (!List.of("minor", "moderate", "serious", "critical").contains(minimumImpact))
            throw new IllegalArgumentException("Invalid a11y minimum impact");
        outputDir = outputDir == null ? "build/a11y" : outputDir;
        pageTemplates = List.copyOf(pageTemplates == null ? List.of("/") : pageTemplates);
        pageTemplates.forEach(A11yProperties::validatePageKey);
        accepted = List.copyOf(accepted == null ? List.of() : accepted);
        if (accepted.stream().map(Accepted::key).distinct().count() != accepted.size())
            throw new IllegalArgumentException("Accepted a11y keys must be unique");
        selectorAliases = Map.copyOf(selectorAliases == null ? Map.of() : selectorAliases);
        selectorAliases.values().forEach(alias -> { if (!alias.matches("[A-Za-z0-9_. /#>-]{1,200}"))
            throw new IllegalArgumentException("Selector aliases must be reviewed static labels"); });
    }
    static void validatePageKey(String key) {
        if (key == null || !key.matches("/[A-Za-z0-9_/{}/.-]*") || key.contains("//") || key.contains(".."))
            throw new IllegalArgumentException("Use an explicit URL path template as page key, without query or values");
    }
}
