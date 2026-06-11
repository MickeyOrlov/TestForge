package io.testforge.example;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.microsoft.playwright.Page;
import io.testforge.web.playwright.PlaywrightPageExtension;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Locator conventions on a real browser: stable hooks first
 * ({@code data-testid}), roles second, CSS last. WireMock plays the web
 * application, so the test needs no environment — only a browser binary.
 *
 * <p>Tagged {@code browser}, excluded from the default build — run with
 * {@code ./gradlew :example-tests:browsersTest} after
 * {@code ./gradlew :module-web:playwrightInstall}.
 */
@SpringBootTest(properties = "forge.playwright.enabled=true")
@Tag("browser")
@ExtendWith(PlaywrightPageExtension.class)
class PlaywrightSmokeIT {

    static WireMockServer server = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    static {
        server.start();
        server.stubFor(get(urlPathEqualTo("/app")).willReturn(ok("""
                <html><body>
                  <h1>Demo checkout</h1>
                  <button data-testid="pay"
                          onclick="document.getElementById('result').textContent='paid'">
                    Pay
                  </button>
                  <div id="result"></div>
                </body></html>
                """).withHeader("Content-Type", "text/html")));
    }

    @AfterAll
    static void stopServer() {
        server.stop();
    }

    @Test
    void paysThroughTheRealBrowser(Page page) {
        page.navigate("http://localhost:" + server.port() + "/app");

        page.getByTestId("pay").click();

        assertThat(page.locator("#result")).hasText("paid");
    }
}
