package io.testforge.api.fuzz;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.testforge.api.discovery.ApiDiscoveryProperties;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.ResourceLoader;

/**
 * Regression: the real Schemathesis CLI, driven with a <strong>relative</strong>
 * {@code forge.api-fuzz.output-dir}.
 *
 * <p>{@code RealSchemathesisAcceptanceTest} already runs the CLI, but from a
 * JUnit {@code @TempDir}, which is absolute — so the generated
 * {@code --config-file} path was absolute there by accident and the defect was
 * invisible. The documented default is {@code output-dir: build/api-fuzz}, which
 * is relative, and the child process runs with its working directory set to the
 * per-spec output directory. The relative path was therefore resolved against
 * the wrong base and Schemathesis aborted before testing anything, so no
 * external consumer could complete a run.
 *
 * <p>This test pins the configuration that was broken. It executes the real
 * {@code st} binary — the {@code ProcessRunner} here only observes
 * {@link DefaultProcessRunner}, it does not replace it.
 *
 * <p>Excluded from the default build because it needs the CLI installed:
 * {@code ./gradlew :module-api-fuzz:schemathesisTest}
 */
@Tag("schemathesis")
class RealSchemathesisRelativeOutputDirTest {

    /** Deliberately relative: this is the property shape the defect needed. */
    private static final Path RELATIVE_OUTPUT_DIR = Path.of("build", "api-fuzz-relative-e2e");

    private static HttpServer server;
    private static int serverPort;
    private static final List<String> observedMethods = new CopyOnWriteArrayList<>();

    private final ResourceLoader resourceLoader = new DefaultResourceLoader();
    private final NdjsonReportParser reportParser = new NdjsonReportParser();
    private final FuzzEvidenceWriter evidenceWriter = new FuzzEvidenceWriter();

    @BeforeAll
    static void startServer() throws IOException {
        deleteRecursively(RELATIVE_OUTPUT_DIR);

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", exchange -> {
            observedMethods.add(exchange.getRequestMethod().toUpperCase(Locale.ROOT));
            // always a server error, so the run ends with findings and exit code 1
            byte[] body = "{\"error\":\"boom\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(500, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        serverPort = server.getAddress().getPort();
    }

    @AfterAll
    static void stopServerAndClean() throws IOException {
        if (server != null) {
            server.stop(0);
        }
        deleteRecursively(RELATIVE_OUTPUT_DIR);
    }

    @Test
    void aRelativeOutputDirStillCompletesARealSchemathesisRun() throws IOException {
        List<List<String>> commands = new ArrayList<>();
        List<String> stdouts = new ArrayList<>();
        DefaultProcessRunner real = new DefaultProcessRunner();
        ProcessRunner observing = (command, workingDir, env, timeout) -> {
            ProcessResult result = real.run(command, workingDir, env, timeout);
            commands.add(List.copyOf(command));
            stdouts.add(result.stdout());
            return result;
        };

        ApiDiscoveryProperties discovery = new ApiDiscoveryProperties(null, null, null, null, null, Map.of(
                "relative", new ApiDiscoveryProperties.Spec("classpath:/openapi/relative-output-api.yaml")));
        ApiFuzzProperties properties = new ApiFuzzProperties(
                true,                              // enabled
                RELATIVE_OUTPUT_DIR.toString(),    // outputDir — relative, the point of this test
                List.of("relative"),               // specs
                "http://127.0.0.1:" + serverPort,  // baseUrl
                null,                              // methods: default GET, HEAD, OPTIONS
                false,                             // allowUnsafeMethods: the second key stays off
                List.of("coverage", "fuzzing"),    // phases
                4242L,                             // seed — deterministic
                5,                                 // maxExamples — bounded
                "all",                             // generationMode
                null,                              // maxFailures
                60,                                // timeoutSeconds
                "st",                              // command
                null,                              // configFile
                null);

        ApiFuzzReport report = new ApiFuzzRunner(
                new FuzzSpecMaterializer(discovery, resourceLoader, RELATIVE_OUTPUT_DIR),
                new SchemathesisExecutor(observing, "st"),
                reportParser,
                evidenceWriter,
                discovery,
                properties).run();

        // (3) the generated config was handed over as an absolute path and the
        // CLI accepted it — the exact failure this regresses says so in stdout
        List<String> runCommand = commands.stream()
                .filter(command -> command.contains("run"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the CLI was never invoked with the run subcommand"));
        String configArgument = runCommand.get(runCommand.indexOf("--config-file") + 1);

        assertThat(Path.of(configArgument)).isAbsolute();
        assertThat(Path.of(configArgument))
                .as("the file the CLI was pointed at must be the one TestForge wrote")
                .exists();
        assertThat(Files.readString(Path.of(configArgument)))
                .contains("unexpected-methods");
        assertThat(stdouts)
                .as("this is the message the defect produced")
                .noneMatch(out -> out.contains("Failed to load configuration file"));

        // (6) exit code 1 with findings is a result, not an infrastructure failure
        assertThat(report.outcome()).isEqualTo(ApiFuzzOutcome.FINDINGS);
        assertThat(report.errors()).isEmpty();
        assertThat(report.findings()).isNotEmpty();
        assertThat(report.findings()).anySatisfy(finding ->
                assertThat(finding.checkName()).isEqualTo("not_a_server_error"));

        // (4) the NDJSON report exists and carries content
        Path ndjson = report.artifacts().get("relative/report.ndjson");
        assertThat(ndjson).isNotNull();
        assertThat(ndjson).exists();
        assertThat(Files.size(ndjson)).isGreaterThan(0L);

        // (5) the two-key policy held: the spec declares POST and nothing sent it
        assertThat(observedMethods).isNotEmpty();
        assertThat(observedMethods).allSatisfy(method ->
                assertThat(ApiFuzzProperties.SAFE_METHODS).contains(method));
        assertThat(observedMethods)
                .as("POST is in the document; allow-unsafe-methods stayed false")
                .doesNotContain("POST");
        assertThat(runCommand).doesNotContainSequence("--include-method", "POST");
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
