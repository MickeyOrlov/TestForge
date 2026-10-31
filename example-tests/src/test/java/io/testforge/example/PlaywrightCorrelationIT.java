package io.testforge.example;

import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.microsoft.playwright.Page;
import io.testforge.core.context.ScenarioContext;
import io.testforge.core.context.ScenarioContextExtension;
import io.testforge.core.context.ScenarioKeys;
import io.testforge.web.playwright.PlaywrightPageExtension;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Relative navigation against {@code forge.playwright.base-url}, and the
 * scenario's correlation id on the browser's requests — to the application's
 * own origin only. The page also loads a script from a second origin (a CDN
 * stand-in), which must not receive the header.
 *
 * <p>Tagged {@code browser}: run with {@code ./gradlew :example-tests:browsersTest}.
 */
@SpringBootTest(properties = "forge.playwright.enabled=true")
@Tag("browser")
@ExtendWith({PlaywrightPageExtension.class, ScenarioContextExtension.class})
class PlaywrightCorrelationIT {

    static WireMockServer app = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    static WireMockServer cdn = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    static {
        app.start();
        cdn.start();
        cdn.stubFor(get(urlPathEqualTo("/lib.js")).willReturn(ok("window.libLoaded = true;")
                .withHeader("Content-Type", "application/javascript")));
        app.stubFor(get(urlPathEqualTo("/api/cart")).willReturn(okJson("{\"total\": \"42.00\"}")));
        app.stubFor(get(urlPathEqualTo("/checkout")).willReturn(ok("""
                <html><head><script src="%s/lib.js"></script></head><body>
                  <div id="total"></div>
                  <script>
                    fetch('/api/cart').then(r => r.json())
                      .then(cart => document.getElementById('total').textContent = cart.total);
                  </script>
                </body></html>
                """.formatted(cdn.baseUrl())).withHeader("Content-Type", "text/html")));
    }

    @DynamicPropertySource
    static void baseUrl(DynamicPropertyRegistry registry) {
        registry.add("forge.playwright.base-url", app::baseUrl);
    }

    @AfterAll
    static void stop() {
        app.stop();
        cdn.stop();
    }

    @Test
    void theBrowsersRequestsCarryTheScenariosIdToTheAppOnly(Page page) {
        page.navigate("/checkout");

        assertThat(page.locator("#total")).hasText("42.00");

        String id = ScenarioContext.find(ScenarioKeys.CORRELATION_ID).orElseThrow();
        app.verify(getRequestedFor(urlPathEqualTo("/checkout")).withHeader("X-Request-Id", equalTo(id)));
        app.verify(getRequestedFor(urlPathEqualTo("/api/cart")).withHeader("X-Request-Id", equalTo(id)));
        cdn.verify(getRequestedFor(urlPathEqualTo("/lib.js")).withHeader("X-Request-Id", absent()));
    }
}
