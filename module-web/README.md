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

Each page's answer is logged and published to the run index as `prewarm.md`:

```text
Prewarm: https://staging.example.test/ -> 200 in 812 ms
Prewarm: https://staging.example.test/app -> 200 at https://staging.example.test/sign-in in 95 ms
Prewarm: https://staging.example.test/reports -> 502 in 31 ms
Prewarm finished: 2 of 3 page(s) answered in 2410 ms
```

"Ended at" a sign-in page means the warm-up met an auth wall, not the page.
URLs are redacted first (user info dropped, query values masked).
`PrewarmRunner.lastReport()` holds the same result for a test that wants it.

### Browser or HTTP

`mode: browser` (default) loads each page in headless Chromium: server-side
rendering, CDN caches *and* the lazily compiled client bundles warm. `mode:
http` sends plain GETs that follow redirects: server, CDN and application
caches and connection pools warm, the page's scripts do not run — and no
browser has to be downloaded or baked into the image. Pick `http` when the
slow first test is slow on the server; keep `browser` when it is the bundle.
`PrewarmExampleTest` runs the HTTP mode end to end against WireMock.

### Stop when the environment is down

```yaml
forge:
  prewarm:
    fail-when-unreachable: true   # default: false
```

When not one page answers — every visit failed or got a 5xx — the context
fails to start with the list of what each page did, instead of every test
failing slowly for that one reason. A 4xx is an answer (a login wall, a
missing page) and does not count as down; a browser that cannot start says
nothing about the environment and only warns.

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
    fail-when-unreachable: false  # default; true stops the suite when no page answers
    mode: browser            # default; http sends plain GETs and needs no browser
```

## CI note

Playwright downloads a browser on first use. Bake
`./gradlew :module-web-playwright:playwrightInstall` into the CI image to
avoid paying the download on every run.

## Agent notes

- Prewarm runs once per JVM and must never fail the suite — a cold
  environment makes tests slower, not wrong; keep that contract.
- Browser binaries are baked into the CI runner image (see Dockerfile);
  don't add per-run downloads to jobs.
