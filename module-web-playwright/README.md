# module-web-playwright

Playwright bootstrap for UI tests: one browser process per JVM, a fresh
isolated `BrowserContext` per test, fixture-style `Page` injection.

## What's inside

- **`PlaywrightProvider`** — launches the configured browser once per JVM;
  `newContext()` returns an isolated context with the default timeout applied.
- **`PlaywrightPageExtension`** — JUnit 5 fixture: declare `Page` in the test
  signature, the extension opens a fresh context and closes it after the test.

## Configuration

```yaml
forge:
  playwright:
    enabled: true          # opt-in: a browser process is expensive
    browser-type: chromium # chromium | firefox | webkit
    headless: true
    default-timeout: 15000
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
`./gradlew :module-web:playwrightInstall`.

## Agent notes

- Keep the module deletable: no other module may depend on it.
- The provider bean is opt-in (`forge.playwright.enabled`) — a browser process
  must never start in a default offline build; browser-backed examples carry
  `@Tag("browser")`.
- Browser binaries belong in the CI runner image, not in per-run downloads.
- Page objects live in the adapted project; this module only owns lifecycle.
