package io.testforge.example;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.testforge.http.ApiClient;
import io.testforge.http.Pagination;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** "All tasks" across pages, without a hand-written loop. */
@SpringBootTest
class PaginationExampleTest {

    record Task(String id, String title) {
    }

    static WireMockServer server = new WireMockServer(WireMockConfiguration.options().dynamicPort().bindAddress("127.0.0.1"));

    static {
        server.start();
    }

    @DynamicPropertySource
    static void forgeProperties(DynamicPropertyRegistry registry) {
        registry.add("forge.http.base-url", () -> "http://localhost:" + server.port());
    }

    @Autowired
    ApiClient api;

    @Test
    void collectsEveryPage() {
        server.resetAll();
        server.stubFor(get(urlPathEqualTo("/api/v1/tasks")).withQueryParam("page", equalTo("1"))
                .willReturn(okJson("{\"items\":[{\"id\":\"t-1\",\"title\":\"a\"},{\"id\":\"t-2\",\"title\":\"b\"}]}")));
        server.stubFor(get(urlPathEqualTo("/api/v1/tasks")).withQueryParam("page", equalTo("2"))
                .willReturn(okJson("{\"items\":[{\"id\":\"t-3\",\"title\":\"c\"}]}")));
        server.stubFor(get(urlPathEqualTo("/api/v1/tasks")).withQueryParam("page", equalTo("3"))
                .willReturn(okJson("{\"items\":[]}")));

        List<Task> all = Pagination.byPageNumber(page -> api.request().queryParam("page", page).get("/api/v1/tasks"))
                .items("items", Task.class)
                .collect();

        assertThat(all).extracting(Task::id).containsExactly("t-1", "t-2", "t-3");
    }
}
