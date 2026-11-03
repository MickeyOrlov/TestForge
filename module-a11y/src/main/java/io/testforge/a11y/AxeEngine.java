package io.testforge.a11y;

import com.deque.html.axecore.playwright.AxeBuilder;
import com.deque.html.axecore.results.Rule;
import com.microsoft.playwright.Page;
import java.util.ArrayList;
import java.util.List;

/** Uses the official Deque Playwright integration and its bundled axe-core. */
public final class AxeEngine implements A11yEngine {
    @Override public Scan analyze(Page page, A11yProperties properties, List<String> include, List<String> exclude) {
        try {
            AxeBuilder builder = new AxeBuilder(page).withTags(properties.tags());
            if (!properties.disabledRules().isEmpty()) builder.disableRules(List.copyOf(properties.disabledRules().keySet()));
            if (!include.isEmpty()) builder.include(include);
            if (!exclude.isEmpty()) builder.exclude(exclude);
            var result = builder.analyze();
            if (result == null || result.getError() != null || result.getErrorMessage() != null
                    || result.getViolations() == null || result.getIncomplete() == null)
                throw new IllegalStateException("axe did not complete");
            // Validate against axe's own rule metadata; unknown tags/rules must not silently run nothing.
            java.util.Set<String> ruleIds = new java.util.HashSet<>(), tags = new java.util.HashSet<>();
            Object metadata = page.evaluate("() => axe.getRules().map(r => ({id:r.ruleId, tags:r.tags}))");
            if (!(metadata instanceof List<?> rules)) throw new IllegalStateException("Missing axe metadata");
            for (Object item : rules) {
                if (!(item instanceof java.util.Map<?, ?> rule) || !(rule.get("id") instanceof String id)
                        || !(rule.get("tags") instanceof List<?> values)) throw new IllegalStateException("Unknown axe metadata");
                ruleIds.add(id); values.forEach(tag -> tags.add(String.valueOf(tag)));
            }
            if (!tags.containsAll(properties.tags()) || !ruleIds.containsAll(properties.disabledRules().keySet())
                    || result.getPasses() == null || result.getPasses().size() + result.getViolations().size()
                        + result.getIncomplete().size() == 0) throw new IllegalStateException("No usable configured axe rules");
            return new Scan(result.getTestEngine().getVersion(), nodes(result.getViolations()), nodes(result.getIncomplete()));
        } catch (RuntimeException e) {
            // Engine errors can quote DOM content or URL query values. Do not propagate them.
            throw new IllegalStateException("axe accessibility scan failed; no usable result");
        }
    }
    private List<Node> nodes(List<Rule> rules) {
        List<Node> nodes = new ArrayList<>();
        for (Rule rule : rules) for (var node : rule.getNodes())
            nodes.add(new Node(rule.getId(), rule.getImpact(), List.copyOf(rule.getTags()), target(node.getTarget()), rule.getHelpUrl()));
        return List.copyOf(nodes);
    }
    private String target(Object target) {
        if (target instanceof String text) return text;
        if (target instanceof List<?> list) return list.stream().map(this::target)
                .collect(java.util.stream.Collectors.joining(" >> "));
        throw new IllegalStateException("Unknown axe target layout");
    }
}
