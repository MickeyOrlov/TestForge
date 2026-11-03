package io.testforge.a11y;

import java.util.List;
import java.util.Map;

/** Sanitized evidence only; raw axe HTML, URLs and page content are never retained. */
public record A11yResult(int formatVersion, String scanId, String pageKey, String scannedAt,
        String engineVersion, String policySha256, List<Finding> violations, List<Finding> incomplete,
        List<String> newBlockingKeys, Map<String, String> disabledRules, String directory) {
    public record Finding(String key, String rule, String impact, List<String> tags, String selector,
            String helpUrl, String disposition) { }
    public boolean passed() { return newBlockingKeys.isEmpty() && incomplete.isEmpty(); }
}
