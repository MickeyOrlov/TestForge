# module-web

UI-environment helpers. For now: prewarm.

## Prewarm

Test environments answer the first page load slowly — cold SSR/CDN caches,
lazy-compiled bundles. Without prewarm the first UI tests of a run fail on
navigation timeouts and their retries mask real bugs. `PrewarmRunner` visits
the configured pages exactly once per JVM, right after the Spring context
starts, before any test runs.

A prewarm failure logs a warning and lets the suite continue: a cold
environment makes tests slower, not wrong.

## Configuration

```yaml
forge:
  prewarm:
    enabled: true            # default: false; bean is not even created when off
    urls:
      - https://staging.example.test/
      - https://staging.example.test/sign-in
    headless: true           # default
    navigation-timeout: 40s  # default
```

## CI note

Playwright downloads a browser on first use. Bake
`npx playwright install chromium` (or the Java equivalent
`mvn/gradle playwright install`) into the CI image to avoid paying the
download on every run.

## Agent notes

- Prewarm runs once per JVM and must never fail the suite — a cold
  environment makes tests slower, not wrong; keep that contract.
- Browser binaries are baked into the CI runner image (see Dockerfile);
  don't add per-run downloads to jobs.
