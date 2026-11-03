package io.testforge.a11y;

import com.microsoft.playwright.Page;
import java.util.List;

/** Narrow seam over axe; never evaluates accessibility rules in TestForge. */
public interface A11yEngine {
    record Node(String rule, String impact, List<String> tags, String selector, String helpUrl) { }
    record Scan(String engineVersion, List<Node> violations, List<Node> incomplete) { }
    Scan analyze(Page page, A11yProperties properties, List<String> include, List<String> exclude);
}
