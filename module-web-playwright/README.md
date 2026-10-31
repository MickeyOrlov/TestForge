# module-web-playwright

Playwright lifecycle for UI tests: one browser process per JVM, a fresh
isolated `BrowserContext` per test, fixture-style `Page` injection, and
failure artifacts for CI diagnostics.

## What's inside

- **`PlaywrightProvider`** — launches the configured browser once per JVM;
  `newContext()` returns an isolated context with the default timeout applied.
- **`PlaywrightPageExtension`** — JUnit 5 fixture: declare `Page` in the test
  signature, the extension opens a fresh context and closes it after the test.
- **Failure artifacts** — on test failure the extension stores
  `browser.md`, `screenshot.png` and `trace.zip` under
  `forge.playwright.artifacts-dir/<Class>-<method>/`.

## Configuration

```yaml
forge:
  playwright:
    enabled: true          # opt-in: a browser process is expensive
    browser-type: chromium # chromium | firefox | webkit
    headless: true
    default-timeout: 15000
    artifacts-on-failure: true
    artifacts-dir: build/playwright-artifacts
    base-url: https://staging.example.test  # relative navigation resolves against it
    correlation: true                       # default; needs base-url
    correlation-header: X-Request-Id        # default, as in module-http
    download-browsers: true                 # default; false in images that bake browsers in
```

### Base URL and correlation

With `base-url` set, tests navigate relatively (`page.navigate("/checkout")`)
and every browser request to that origin carries the scenario's correlation id
— the same `ScenarioKeys.CORRELATION_ID` `module-http` sends, so the backend
log lines of a UI test, its API calls and its reports share one value.
`browser.md` names the id. Requests to other origins (a CDN, an identity
provider) never get the header: a custom header on a cross-origin request
forces a CORS preflight the third party may refuse. An id the page sets itself
wins. The header is added through request routing, which turns off
Playwright's HTTP cache for the context; set `correlation: false` where that
matters more than the id. `PlaywrightCorrelationIT` shows both origins on a
real browser.

## Usage

```java
@SpringBootTest(properties = "forge.playwright.enabled=true")
@ExtendWith(PlaywrightPageExtension.class)
class CheckoutTest {

    @Test
    void paysOrder(Page page) {
        page.navigate(baseUrl + "/checkout");
        page.getByTestId("pay").click();
        assertThat(page.locator("#result")).hasText("paid");
    }
}
```

Locator conventions: stable hooks first (`data-testid` via `getByTestId`),
roles second (`getByRole`), CSS selectors last. See `PlaywrightSmokeIT` in
example-tests — run it with `./gradlew :example-tests:browsersTest` after
`./gradlew :module-web-playwright:playwrightInstall`.

`browser.md` is the text to read first — what the browser saw that a
screenshot cannot show:

```text
# Browser — CheckoutTest-showsTheTotal

- page: https://shop.test/app
- title: Broken checkout

## Failed requests

- GET https://shop.test/api/cart?session=*** -> 500

## Page errors

- Error: cannot render the total

## Console

- error: cart failed with 500
```

It covers every page of the test's context (popups and new tabs too), keeps
the last 50 entries of each kind, redacts URLs, and never records headers or
bodies. `PlaywrightFailureArtifactsIT` shows it on a real browser.

A failure in `@BeforeEach` or `@AfterEach` — a sign-in that does not get
through — leaves the same artifacts as one in the test method, captured once.

Failure artifacts are best-effort diagnostics: if screenshot or trace capture
fails, the original test failure still wins and the artifact error is attached
as a suppressed exception. Upload `build/playwright-artifacts` from browser CI
jobs to inspect failures locally with `npx playwright show-trace trace.zip`.

## Agent notes

- Keep the module deletable: no other module may depend on it.
- The provider bean is opt-in (`forge.playwright.enabled`) — a browser process
  must never start in a default offline build; browser-backed examples carry
  `@Tag("browser")`.
- Browser binaries belong in the CI runner image, not in per-run downloads.
  Playwright itself downloads every bundled browser when one is missing; set
  `forge.playwright.download-browsers=false` in such images so a missing
  browser fails at once with the install command instead.
- Page objects live in the adapted project; this module only owns lifecycle.
- Do not grow a second UI framework here: assertions, page objects, auth state
  and project-specific helpers belong in the adapted test module.
