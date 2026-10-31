# Playwright roadmap

`module-web-playwright` owns the UI test lifecycle: one browser per JVM, a
fresh context per test, a `Page` fixture, a screenshot and trace on failure.
What it did not keep is the text of a failure — the 500 the page's API call
got, the console error, the uncaught exception — so every red UI test meant
opening a trace to learn what one line could have said. And the browser's
requests carried nothing tying them to the scenario's backend logs.

| Step | What | Where | Status |
|---|---|---|---|
| 1 | **The browser's side of a failure** — failed requests, console errors, page errors in `browser.md` next to the screenshot | `module-web-playwright` | shipped |
| 2 | **Base URL and correlation** — relative navigation, and the scenario's correlation id on every browser request | `module-web-playwright` | shipped |
| 3 | **Review** — close the gaps a review of the module finds | `module-web-playwright` | planned |

## Step 1 — the browser's side of a failure

Each test's context feeds a bounded journal: console errors and warnings,
uncaught page errors, requests that failed or answered 4xx/5xx — popups and
new tabs included, since the listeners sit on the context. When the test
fails, `browser.md` is written first (it needs nothing from a page that may
have crashed), then the screenshot and trace, all registered under the test's
`testKey`. URLs are redacted; headers and bodies are never recorded.
`PlaywrightFailureArtifactsIT` proves it on a real browser with a page whose
API call returns 500.

## Step 2 — base URL and correlation

`forge.playwright.base-url` becomes the context's base URL, so tests navigate
relatively. With it set, every request to that origin carries the scenario's
`CORRELATION_ID` in `X-Request-Id` (the `module-http` default; configurable),
generated and stored in `ScenarioContext` if the test has none yet — a UI
test's backend logs and its API calls then share one id, and `browser.md`
names it. The header is added by routing only same-origin requests: a
browser-wide extra header would reach CDNs and identity providers and force
CORS preflights they may refuse. Routing disables Playwright's HTTP cache for
the context, so `correlation: false` remains available.
`PlaywrightCorrelationIT` proves it with two origins on a real browser.
