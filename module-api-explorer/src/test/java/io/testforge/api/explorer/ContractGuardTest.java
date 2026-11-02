package io.testforge.api.explorer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import io.testforge.api.discovery.TestForgeApiDiscoveryAutoConfiguration;
import io.testforge.artifact.ArtifactSink;
import io.testforge.artifact.TestArtifact;
import io.testforge.core.TestForgeCoreAutoConfiguration;
import io.testforge.http.ApiClient;
import io.testforge.http.TestForgeHttpAutoConfiguration;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Ordinary ApiClient calls, checked against explorer-demo.yaml as they happen. */
class ContractGuardTest {

    private HttpServer server;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String body;
            int status = 200;
            if (path.equals("/tasks")) {
                body = "{\"items\":[{\"id\":\"t-1\",\"title\":\"ok\"}]}";
            } else if (path.equals("/tasks/broken")) {
                body = "{\"id\":\"broken\"}";                      // title is required by the document
            } else if (path.startsWith("/tasks/")) {
                body = "{\"id\":\"" + path.substring(7) + "\",\"title\":\"ok\"}";
            } else {
                body = "{}";
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private ApplicationContextRunner runner(String... extra) {
        List<String> properties = new ArrayList<>(List.of(
                "forge.http.base-url=http://127.0.0.1:" + server.getAddress().getPort(),
                "forge.api-discovery.specs.demo.location=" + ExplorerFixtures.LOCATION,
                "forge.contract-guard.enabled=true"));
        properties.addAll(List.of(extra));
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(TestForgeCoreAutoConfiguration.class,
                        TestForgeHttpAutoConfiguration.class, TestForgeApiDiscoveryAutoConfiguration.class,
                        TestForgeContractGuardAutoConfiguration.class))
                .withPropertyValues(properties.toArray(String[]::new));
    }

    @Test
    void theSuiteLearnsWhichDocumentedOperationsItNeverCalled(@TempDir Path temp) throws Exception {
        List<TestArtifact> registered = new ArrayList<>();
        ArtifactSink sink = new ArtifactSink() {
            @Override
            public Path directoryFor(String source) {
                return temp.resolve(source);
            }

            @Override
            public void register(TestArtifact artifact) {
                registered.add(artifact);
            }

            @Override
            public TestArtifact write(String source, String category, String name, String mediaType, String content) {
                throw new UnsupportedOperationException();
            }
        };
        runner().withBean(ArtifactSink.class, () -> sink).run(context -> {
            ApiClient api = context.getBean(ApiClient.class);
            api.request().get("/tasks");
            api.request().get("/tasks");
            api.request().get("/tasks/t-1");

            ContractGuard.Coverage coverage = context.getBean(ContractGuard.class).coverage();
            assertThat(coverage.called()).containsExactly(
                    java.util.Map.entry("demo GET /tasks", 2L), java.util.Map.entry("demo GET /tasks/{taskId}", 1L));
            assertThat(coverage.uncalled()).contains("demo POST /tasks", "demo DELETE /tasks/{taskId}")
                    .doesNotContain("demo GET /tasks");
        });

        assertThat(registered).filteredOn(artifact -> artifact.name().equals("api-coverage.md"))
                .singleElement().satisfies(artifact -> {
                    assertThat(artifact.metadata()).containsEntry("healthy", "true");
                    assertThat(Files.readString(artifact.file()))
                            .contains("## Never called").contains("- `demo POST /tasks`")
                            .contains("- `demo GET /tasks` 2×");
                });
    }

    @Test
    void conformingResponsesLeaveNoFinding() {
        runner().run(context -> {
            ApiClient api = context.getBean(ApiClient.class);
            api.request().get("/tasks").then().statusCode(200);
            api.request().get("/tasks/t-1").then().statusCode(200);

            ContractGuard guard = context.getBean(ContractGuard.class);
            assertThat(guard.checkedResponses()).isEqualTo(2);
            assertThat(guard.findings()).isEmpty();
        });
    }

    @Test
    void recordModeCollectsDriftWithoutFailingTheTest() {
        runner().run(context -> {
            ApiClient api = context.getBean(ApiClient.class);
            api.request().get("/tasks/broken").then().statusCode(200);
            api.request().get("/tasks/broken").then().statusCode(200);
            api.request().get("/nowhere").then().statusCode(200);

            assertThat(context.getBean(ContractGuard.class).findings())
                    .extracting(ContractGuard.Finding::operation, ContractGuard.Finding::kind,
                            ContractGuard.Finding::location, ContractGuard.Finding::count)
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple("GET /tasks/{taskId}", "MISSING_REQUIRED_FIELD",
                                    "$.title", 2L),
                            org.assertj.core.groups.Tuple.tuple("GET /nowhere",
                                    ContractGuard.UNDOCUMENTED_OPERATION, "$", 1L));
        });
    }

    /**
     * The document describes the API at {@code forge.http.base-url}. A call the
     * same ApiClient sends to another service ({@code request("payments")}, a
     * downstream mock) is not an undocumented operation of this API.
     */
    @Test
    void callsToAnotherServiceAreNotJudgedAgainstThisDocument() throws IOException {
        HttpServer payments = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        payments.createContext("/", exchange -> {
            byte[] bytes = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        payments.start();
        try {
            runner("forge.http.services.payments.base-url=http://127.0.0.1:" + payments.getAddress().getPort())
                    .run(context -> {
                        ApiClient api = context.getBean(ApiClient.class);
                        api.request("payments").get("/payments/p-1").then().statusCode(200);
                        api.request().get("/tasks").then().statusCode(200);

                        ContractGuard guard = context.getBean(ContractGuard.class);
                        assertThat(guard.findings()).isEmpty();
                        assertThat(guard.checkedResponses()).isEqualTo(1);
                    });
        } finally {
            payments.stop(0);
        }
    }

    @Test
    void failModeFailsTheCallThatReturnedADriftedResponse() {
        runner("forge.contract-guard.mode=fail").run(context -> {
            ApiClient api = context.getBean(ApiClient.class);
            api.request().get("/tasks/t-1").then().statusCode(200);

            assertThatThrownBy(() -> api.request().get("/tasks/broken"))
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("GET /tasks/broken does not match the OpenAPI document")
                    .hasMessageContaining("MISSING_REQUIRED_FIELD at $.title");
        });
    }

    @Test
    void driftIsPublishedToTheRunIndexWithAVerdict(@TempDir Path temp) throws Exception {
        List<TestArtifact> registered = new ArrayList<>();
        ArtifactSink sink = new ArtifactSink() {
            @Override
            public Path directoryFor(String source) {
                return temp.resolve(source);
            }

            @Override
            public void register(TestArtifact artifact) {
                registered.add(artifact);
            }

            @Override
            public TestArtifact write(String source, String category, String name, String mediaType, String content) {
                throw new UnsupportedOperationException();
            }
        };
        runner().withBean(ArtifactSink.class, () -> sink).run(context ->
                context.getBean(ApiClient.class).request().get("/tasks/broken"));

        assertThat(registered).filteredOn(artifact -> artifact.name().equals("contract-guard.json"))
                .singleElement().satisfies(artifact -> {
            assertThat(artifact.metadata()).containsEntry("healthy", "false").containsEntry("checked", "1");
            assertThat(Files.readString(artifact.file())).contains("MISSING_REQUIRED_FIELD").contains("$.title");
        });
    }

    @Test
    void inertUnlessEnabled() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(TestForgeContractGuardAutoConfiguration.class))
                .run(context -> assertThat(context).doesNotHaveBean(ContractGuardFilter.class));
    }

    @Test
    void templatesMatchWholeSegmentsOnly() {
        assertThat(ContractGuard.pattern("/tasks/{taskId}").matcher("/tasks/42").matches()).isTrue();
        assertThat(ContractGuard.pattern("/tasks/{taskId}").matcher("/tasks/42/history").matches()).isFalse();
        assertThat(ContractGuard.pattern("/a.b/{x}").matcher("/aXb/1").matches()).as("dots are literal").isFalse();
        assertThat(ContractGuard.pattern("/tasks").matcher("/tasks/").matches()).isTrue();
    }
}
