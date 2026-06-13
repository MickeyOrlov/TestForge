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
  `screenshot.png` and `trace.zip` under `forge.playwright.artifacts-dir`.

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
```

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
- Page objects live in the adapted project; this module only owns lifecycle.
- Do not grow a second UI framework here: assertions, page objects, auth state
  and project-specific helpers belong in the adapted test module.
