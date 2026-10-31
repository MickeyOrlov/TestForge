package io.testforge.example;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.serverError;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.microsoft.playwright.Page;
import io.testforge.web.playwright.PlaywrightPageExtension;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Fails on purpose, run only through {@code PlaywrightFailureArtifactsIT}: a
 * page whose API call returns 500, which logs a console error and throws.
 */
@SpringBootTest(properties = {
        "forge.playwright.enabled=true",
        "forge.playwright.default-timeout=3000",
        "forge.playwright.artifacts-dir=build/playwright-artifacts-fixture"
})
@ExtendWith(PlaywrightPageExtension.class)
class PlaywrightFailureTestkitFixture {

    static WireMockServer server = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    static {
        server.start();
        server.stubFor(get(urlPathEqualTo("/api/cart")).willReturn(serverError()));
        server.stubFor(get(urlPathEqualTo("/app")).willReturn(ok("""
                <html><head><title>Broken checkout</title></head><body>
                  <div id="total"></div>
                  <script>
                    fetch('/api/cart?session=s3cret').then(r => {
                      if (!r.ok) { console.error('cart failed with ' + r.status); }
                      throw new Error('cannot render the total');
                    });
                  </script>
                </body></html>
                """).withHeader("Content-Type", "text/html")));
    }

    @AfterAll
    static void stopServer() {
        server.stop();
    }

    @Test
    void showsTheTotal(Page page) {
        page.navigate("http://localhost:" + server.port() + "/app");

        assertThat(page.locator("#total")).hasText("42.00");
    }
}
