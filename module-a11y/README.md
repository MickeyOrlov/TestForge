# module-a11y

Optional accessibility checks using Deque's official [axe-core Playwright Java integration](https://github.com/dequelabs/axe-core-maven-html/tree/develop/playwright).
axe performs the WCAG analysis; TestForge adds opt-in wiring, adoption policy and sanitized evidence.
Pinned integration 4.11.3 works with this project's Playwright 1.59.0 without upgrading the existing browser module.
The upstream integration and bundled axe-core are MPL-2.0. No rule engine or browser lifecycle is implemented here.

## Configuration and use

```yaml
forge:
  a11y:
    enabled: true
    tags: [wcag2a, wcag2aa]
    minimum-impact: serious
    page-templates: [/checkout, /orders/{id}]
    baseline-file: a11y-baseline.json
    output-dir: build/a11y
    disabled-rules:
      region: A11Y-12 reviewed temporary exception
    accepted:
      - key: '/checkout image-alt <selector-sha256-from-report>'
        reason: A11Y-17
        until: 2026-12-01
```

```java
A11yResult result = a11y.check(page);
a11y.assertNoNewViolations(page);
// Or use a reviewed explicit path template, including for about:blank/local static fixtures:
a11y.check(page, "/orders/{id}", List.of("main"), List.of(".third-party-widget"));
```

The caller owns page navigation, readiness and the existing Page fixture. Nothing changes in
module-web-playwright. No browser starts during bean creation or the default build.
`check` reports findings; `assertNoNewViolations` publishes evidence then fails on new findings
at/above minimum-impact or any incomplete/manual-review node. Unknown native tags/rules and scans that execute no applicable rules fail closed. Lesser impacts remain visible.
These are automated results, not a WCAG compliance certification; manual testing is still necessary.

`check(page)` resolves exactly one configured path template against the current URL's path,
then discards the real URL and path values. Ambiguous/unmapped paths fail before scanning. The
explicit page-key overload trusts the reviewed template supplied by the project; never put user
identities or actual dynamic values in that key. Default template is `/` only.

## Adoption

Review the first scan and explicitly call `a11y.writeBaseline(result)`. It writes only stable keys
and impacts; checks never rewrite it. A key combines page template, native rule ID and SHA-256 of
the normalized (trimmed) selector path; frame/shadow boundaries are retained. Higher impact on a
known key is new. Promoting one page preserves other page baselines and replaces the current page's resolved findings. Unknown impact is conservatively blocking. Incomplete scans cannot be promoted.
A missing baseline treats every violation as new; malformed/incompatible baselines fail closed.

Baselines bind to tags, disabled rules, include/exclude selectors, severity policy, selector aliases
and the pinned integration version. To change that policy, retain the old baseline for review and
capture/promote a new baseline explicitly. Use separate baseline files for different scan policies.
Accepted entries match exact finding keys and require a reason and expiry (UTC, valid through
that date). Expired acceptance makes that key new even if an older baseline contains it. No regex or wildcard acceptance.

Generated selectors with dynamic IDs should use project-reviewed `selector-aliases` mapping exact
axe target strings to static semantic labels. Avoid overly broad aliases that merge unrelated nodes.
Reports redact quoted attribute values and UUIDs; keys hash the original/aliased selector so
redaction cannot merge baseline identities. Selectors can themselves carry sensitive IDs/classes:
use static test hooks or reviewed aliases for such pages. No HTML, page text, form values,
screenshots, cookies, request/response payloads or full page URLs are recorded by this module.

Each scan produces a UUID-directory a11y.json and a11y.md and registers both in ArtifactSink when
available. Evidence includes native rules/impacts/tags, redacted selector, page key, help link,
new/baseline/accepted disposition, disabled-rule reasons and manual-review nodes. Viewer/session
recordings remain outside TestForge. There is no module-quality dependency or integration.

```bash
./gradlew :module-a11y:test :architecture-tests:test
./gradlew :module-web-playwright:playwrightInstall  # explicit browser installation if needed
./gradlew :example-tests:a11yTest
./gradlew build
```

A11yAxeIT uses an owned static page, the existing Playwright Page extension and real axe:
missing alt and low contrast fail; explicit adoption passes; a new unlabelled input fails again.
