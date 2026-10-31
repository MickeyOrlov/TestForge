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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Fails on purpose in {@code @BeforeEach}, run only through
 * {@code PlaywrightFailureArtifactsIT}: the sign-in never succeeds.
 */
@SpringBootTest(properties = {
        "forge.playwright.enabled=true",
        "forge.playwright.default-timeout=3000",
        "forge.playwright.artifacts-dir=build/playwright-artifacts-fixture"
})
@ExtendWith(PlaywrightPageExtension.class)
class PlaywrightSignInFailureTestkitFixture {

    static WireMockServer server = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    static {
        server.start();
        server.stubFor(get(urlPathEqualTo("/sign-in")).willReturn(ok(
                "<html><head><title>Sign in</title></head><body><p>Invalid password</p></body></html>")
                .withHeader("Content-Type", "text/html")));
    }

    @AfterAll
    static void stopServer() {
        server.stop();
    }

    @BeforeEach
    void signIn(Page page) {
        page.navigate("http://localhost:" + server.port() + "/sign-in");
        assertThat(page.locator("body")).containsText("Welcome");
    }

    @Test
    void opensTheDashboard(Page page) {
        assertThat(page.locator("h1")).hasText("Dashboard");
    }
}
