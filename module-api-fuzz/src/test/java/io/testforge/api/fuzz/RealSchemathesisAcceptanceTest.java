package io.testforge.api.fuzz;

import com.sun.net.httpserver.HttpServer;
import io.testforge.api.discovery.ApiDiscoveryProperties;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.ResourceLoader;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Acceptance test executing the real Schemathesis CLI process against an in-process HTTP server fixture.
 *
 * <p>Excluded from the default build because it requires the real {@code st} CLI binary installed locally.
 *
 * <p>To run this test manually:
 * <pre>
 * ./gradlew :module-api-fuzz:schemathesisTest
 * </pre>
 */
@Tag("schemathesis")
class RealSchemathesisAcceptanceTest {

    private static HttpServer server;
    private static int serverPort;
    private static final List<String> observedMethods = new CopyOnWriteArrayList<>();

    private final ResourceLoader resourceLoader = new DefaultResourceLoader();
    private final NdjsonReportParser reportParser = new NdjsonReportParser();
    private final FuzzEvidenceWriter evidenceWriter = new FuzzEvidenceWriter();
    private final DefaultProcessRunner processRunner = new DefaultProcessRunner();
    private final SchemathesisExecutor executor = new SchemathesisExecutor(processRunner, "st");

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", exchange -> {
            String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);
            observedMethods.add(method);
            String path = exchange.getRequestURI().getPath();

            if (path.startsWith("/api/buggy")) {
                // Returns 500 Internal Server Error to trigger Schemathesis not_a_server_error check
                byte[] body = "{\"error\":\"Internal Server Error\"}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(500, body.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(body);
                }
            } else {
                // /api/clean or any other path returns 200 OK
                byte[] body = "{\"status\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(body);
                }
            }
        });
        server.start();
        serverPort = server.getAddress().getPort();
    }

    @AfterAll
    static void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void buggyAndCleanScenariosWithRealSchemathesisCli(@TempDir Path tempDir) throws IOException {
        long fixedSeed = 424242L;
        String baseUrl = "http://127.0.0.1:" + serverPort;

        // --- Scenario 4(a) & 5: Buggy Fixture & Reproducibility (Run 1) ---
        Path buggyOutputDir1 = tempDir.resolve("buggy-run-1");
        ApiDiscoveryProperties buggyDiscoveryProps1 = new ApiDiscoveryProperties(null, null, null, null, null, Map.of(
                "buggy", new ApiDiscoveryProperties.Spec("classpath:/openapi/buggy-api.yaml")
        ));
        FuzzSpecMaterializer materializer1 = new FuzzSpecMaterializer(buggyDiscoveryProps1, resourceLoader, buggyOutputDir1);

        ApiFuzzProperties buggyProps1 = new ApiFuzzProperties(
                true,                           // enabled
                buggyOutputDir1.toString(),     // outputDir
                List.of("buggy"),               // specs
                baseUrl,                        // baseUrl
                null,                           // methods (default: GET, HEAD, OPTIONS)
                false,                          // allowUnsafeMethods
                List.of("coverage", "fuzzing"), // phases
                fixedSeed,                      // seed
                5,                              // maxExamples
                "all",                          // generationMode
                null,                           // maxFailures
                15,                             // timeoutSeconds
                "st",                           // command
                null                            // configFile
        ,
                null);

        ApiFuzzRunner runner1 = new ApiFuzzRunner(materializer1, executor, reportParser, evidenceWriter, buggyDiscoveryProps1, buggyProps1);
        ApiFuzzReport report1 = runner1.run();

        // 1. Assert outcome is FINDINGS
        assertThat(report1.outcome()).isEqualTo(ApiFuzzOutcome.FINDINGS);
        assertThat(report1.hasFindings()).isTrue();
        assertThat(report1.findings()).isNotEmpty();

        // 2. Assert at least one finding names the operation
        ApiFuzzFinding finding1 = report1.findings().getFirst();
        assertThat(finding1.operationLabel()).contains("/api/buggy");
        assertThat(finding1.checkName()).isEqualTo("not_a_server_error");

        // 3. Assert JUnit and NDJSON report files were produced on disk and non-empty
        Path ndjsonReport = report1.artifacts().get("buggy/report.ndjson");
        assertThat(ndjsonReport).isNotNull().exists();
        assertThat(Files.size(ndjsonReport)).isGreaterThan(0L);

        List<Path> xmlReports;
        try (var stream = Files.walk(buggyOutputDir1)) {
            xmlReports = stream.filter(p -> p.toString().endsWith(".xml")).toList();
        }
        assertThat(xmlReports).isNotEmpty();
        assertThat(Files.size(xmlReports.getFirst())).isGreaterThan(0L);

        // 4. Assert recorded evidence contains real Schemathesis version (starts with "4.") and the seed
        assertThat(report1.schemathesisVersion()).isNotNull().startsWith("4.");
        assertThat(report1.seed()).isEqualTo(fixedSeed);

        Path runJsonPath1 = buggyOutputDir1.resolve(report1.runId()).resolve(report1.specId()).resolve("run.json");
        assertThat(runJsonPath1).exists();
        String runJsonContent1 = Files.readString(runJsonPath1);
        assertThat(runJsonContent1).contains("\"schemathesisVersion\" : \"4.");
        assertThat(runJsonContent1).contains("\"seed\" : " + fixedSeed);

        // --- Scenario 5: Reproducibility (Run 2 with same seed) ---
        Path buggyOutputDir2 = tempDir.resolve("buggy-run-2");
        ApiDiscoveryProperties buggyDiscoveryProps2 = new ApiDiscoveryProperties(null, null, null, null, null, Map.of(
                "buggy", new ApiDiscoveryProperties.Spec("classpath:/openapi/buggy-api.yaml")
        ));
        FuzzSpecMaterializer materializer2 = new FuzzSpecMaterializer(buggyDiscoveryProps2, resourceLoader, buggyOutputDir2);

        ApiFuzzProperties buggyProps2 = new ApiFuzzProperties(
                true,
                buggyOutputDir2.toString(),
                List.of("buggy"),
                baseUrl,
                null,
                false,
                List.of("coverage", "fuzzing"),
                fixedSeed,
                5,
                "all",
                null,
                15,
                "st",
                null
        ,
                null);

        ApiFuzzRunner runner2 = new ApiFuzzRunner(materializer2, executor, reportParser, evidenceWriter, buggyDiscoveryProps2, buggyProps2);
        ApiFuzzReport report2 = runner2.run();

        assertThat(report2.outcome()).isEqualTo(ApiFuzzOutcome.FINDINGS);
        assertThat(report2.seed()).isEqualTo(fixedSeed);
        assertThat(report2.findings()).isNotEmpty();

        ApiFuzzFinding finding2 = report2.findings().getFirst();
        assertThat(finding2.checkName()).isEqualTo(finding1.checkName());
        assertThat(finding2.operationLabel()).isEqualTo(finding1.operationLabel());
        assertThat(finding2.path()).isEqualTo(finding1.path());
        assertThat(finding2.method()).isEqualTo(finding1.method());

        // --- Scenario 4(b): Clean Fixture ---
        Path cleanOutputDir = tempDir.resolve("clean-run");
        ApiDiscoveryProperties cleanDiscoveryProps = new ApiDiscoveryProperties(null, null, null, null, null, Map.of(
                "clean", new ApiDiscoveryProperties.Spec("classpath:/openapi/clean-api.yaml")
        ));
        FuzzSpecMaterializer cleanMaterializer = new FuzzSpecMaterializer(cleanDiscoveryProps, resourceLoader, cleanOutputDir);

        ApiFuzzProperties cleanProps = new ApiFuzzProperties(
                true,
                cleanOutputDir.toString(),
                List.of("clean"),
                baseUrl,
                null,
                false,
                List.of("coverage", "fuzzing"),
                fixedSeed,
                5,
                "all",
                null,
                15,
                "st",
                null
        ,
                null);

        ApiFuzzRunner cleanRunner = new ApiFuzzRunner(cleanMaterializer, executor, reportParser, evidenceWriter, cleanDiscoveryProps, cleanProps);
        ApiFuzzReport cleanReport = cleanRunner.run();

        assertThat(cleanReport.outcome()).isEqualTo(ApiFuzzOutcome.PASSED);
        assertThat(cleanReport.hasFindings()).isFalse();
        assertThat(cleanReport.findings()).isEmpty();

        // --- Requirement 6: Safety ---
        assertThat(observedMethods)
                .describedAs("Observed HTTP methods sent to fixture server")
                .isNotEmpty()
                .allSatisfy(method -> assertThat(ApiFuzzProperties.SAFE_METHODS).contains(method));
    }
}
