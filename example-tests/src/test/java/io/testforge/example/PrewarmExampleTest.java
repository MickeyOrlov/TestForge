package io.testforge.example;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.testforge.web.prewarm.PrewarmReport;
import io.testforge.web.prewarm.PrewarmRunner;
import io.testforge.web.prewarm.PrewarmVisit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Prewarm end to end, offline: the pages are WireMock stubs, the visits plain
 * GETs ({@code mode: http}, no browser download). It runs while the context
 * starts, before any test — this test only reads what it found.
 */
@SpringBootTest(properties = {
        "forge.prewarm.enabled=true",
        "forge.prewarm.mode=http",
        "forge.prewarm.navigation-timeout=5s"
})
class PrewarmExampleTest {

    static WireMockServer pages = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    static {
        pages.start();
        pages.stubFor(get("/").willReturn(aResponse().withStatus(200).withBody("<html>home</html>")));
        // the warm-up met a login wall: it answered, but not with the page asked for
        pages.stubFor(get("/reports").willReturn(aResponse().withStatus(302).withHeader("Location", "/sign-in")));
        pages.stubFor(get("/sign-in").willReturn(aResponse().withStatus(200).withBody("<html>sign in</html>")));
    }

    @DynamicPropertySource
    static void prewarmUrls(DynamicPropertyRegistry registry) {
        registry.add("forge.prewarm.urls[0]", () -> pages.baseUrl() + "/");
        registry.add("forge.prewarm.urls[1]", () -> pages.baseUrl() + "/reports");
    }

    @AfterAll
    static void stop() {
        pages.stop();
    }

    @Autowired
    PrewarmRunner prewarm;

    @Test
    void everyPageWasVisitedBeforeTheTestsAndItsAnswerIsKept() {
        PrewarmReport report = prewarm.lastReport();

        assertThat(report.skipped()).isNull();
        assertThat(report.visits()).extracting(PrewarmVisit::status).containsExactly(200, 200);
        assertThat(report.visits().get(1).finalUrl()).isEqualTo(pages.baseUrl() + "/sign-in");
        assertThat(report.visits().get(1).redirected()).isTrue();
        assertThat(report.environmentReached()).isTrue();
        pages.verify(1, getRequestedFor(urlEqualTo("/")));
    }
}
