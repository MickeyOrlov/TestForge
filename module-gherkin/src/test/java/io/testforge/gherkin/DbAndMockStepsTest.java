package io.testforge.gherkin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.testforge.core.context.ScenarioContext;
import io.testforge.core.context.ScenarioKeys;
import io.testforge.core.wait.WaitProperties;
import io.testforge.core.wait.Waiter;
import io.testforge.db.state.DbState;
import io.testforge.gherkin.db.DbSteps;
import io.testforge.gherkin.mock.MockSteps;
import io.testforge.mock.ScopedMockClient;
import io.testforge.mock.ScopeMarker;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class DbAndMockStepsTest {

    private final GherkinProperties properties = new GherkinProperties(null, null, null, Duration.ofMillis(200));
    private final ScenarioVariables variables = new ScenarioVariables();

    @AfterEach
    void clear() {
        ScenarioContext.clear();
    }

    @Test
    void databaseStepsFindRowsWatchATableAndCatchAnUnwantedRow() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID()
                + ";DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE orders (id VARCHAR(16) PRIMARY KEY, customer_id BIGINT, status VARCHAR(8), "
                + "total NUMERIC(10,2))");
        jdbc.update("INSERT INTO orders VALUES ('o-1', 7, 'NEW', 10)");
        DbSteps steps = new DbSteps(DbState.of(dataSource),
                new Waiter(new WaitProperties(Duration.ofMillis(300), Duration.ofMillis(20))), variables, properties);
        variables.remember("orderId", "o-1");

        steps.watch("orders", "id", "customer_id", "7");
        jdbc.update("UPDATE orders SET status = 'PAID' WHERE id = 'o-1'");

        steps.hasRow("orders", "customer_id", "7");
        steps.rowHas("orders", "id", "${orderId}", "status", "PAID");
        steps.changed("orders", 0, 1, 0);
        steps.hasNoRow("orders", "status", "REFUNDED");
        assertThatThrownBy(() -> steps.hasNoRow("orders", "status", "PAID"))
                .hasMessageStartingWith("table orders has a row where status is PAID, expected none");
        assertThatThrownBy(() -> steps.rowHas("orders", "id", "o-1", "status", "SHIPPED"))
                .isInstanceOf(AssertionError.class).hasMessageContaining("table orders has no row where");
        assertThatThrownBy(() -> steps.hasRow("orders; DROP TABLE orders", "id", "x"))
                .hasMessageContaining("is not a plain table or column name");
        assertThatThrownBy(() -> steps.unchanged("customers"))
                .hasMessageContaining("Table customers is not watched in this scenario");
    }

    @Test
    void mockStepsStubAndVerifyInTheScenariosScopeOnly() throws Exception {
        WireMockServer server = new WireMockServer(WireMockConfiguration.options().dynamicPort()
                .bindAddress("127.0.0.1"));
        server.start();
        try {
            ScopedMockClient client = new ScopedMockClient(new WireMock("127.0.0.1", server.port()),
                    ScopeMarker.header("X-Test-Scope"), null);
            StaticListableBeanFactory beans = new StaticListableBeanFactory();
            beans.addBean("mocks", client);
            ScenarioContext.put(ScenarioKeys.TEST_SCOPE, "bdd-mine");
            MockSteps steps = new MockSteps(beans.getBeanProvider(ScopedMockClient.class), variables);

            steps.answers("get", "/stock/book", 200, "{\"available\": 3}");

            assertThat(get(server.port(), "bdd-mine")).isEqualTo(200);
            assertThat(get(server.port(), "bdd-other")).as("another scenario's call is not answered").isEqualTo(404);
            steps.received(1, "GET", "/stock/book");
            steps.receivedNone("POST", "/stock/book");
            assertThatThrownBy(() -> steps.received(2, "GET", "/stock/book")).isInstanceOf(AssertionError.class);
            steps.closeScope();
            assertThat(server.listAllStubMappings().getMappings()).as("the scope's stubs go with it").isEmpty();
        } finally {
            server.stop();
        }
    }

    private static int get(int port, String scope) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                + "/stock/book")).header("X-Test-Scope", scope).GET().build(), HttpResponse.BodyHandlers.discarding())
                .statusCode();
    }
}
