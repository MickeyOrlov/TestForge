package io.testforge.example;

import static org.assertj.core.api.Assertions.assertThat;

import io.testforge.security.SecurityCoverage;
import io.testforge.security.SecurityFinding;
import io.testforge.security.SecurityRunner;
import io.testforge.security.SecurityTransportRunner;
import io.testforge.security.Severity;
import io.testforge.security.TestForgeSecurityAutoConfiguration;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

/**
 * The pinned testssl.sh container against an owned HTTPS server on loopback. The task maps
 * tls.testforge.test to 127.0.0.1 for this JVM (jdk.net.hosts.file); host-aliases maps it to the
 * Docker host for the container, so both see the certificate's name.
 */
@Tag("testssl")
class SecurityTestsslIT {
    private static final String HOST = "tls.testforge.test";

    @TempDir Path temp;

    private SSLContext selfSigned() throws Exception {
        String password = UUID.randomUUID().toString();
        Path store = temp.resolve("tls.p12");
        Process keytool =
                new ProcessBuilder(
                                Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                                "-genkeypair", "-alias", "tls", "-keyalg", "RSA", "-keysize", "2048",
                                "-dname", "CN=" + HOST, "-ext", "SAN=dns:" + HOST, "-validity", "10",
                                "-storetype", "PKCS12", "-keystore", store.toString(),
                                "-storepass", password, "-keypass", password, "-noprompt")
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .start();
        assertThat(keytool.waitFor(30, TimeUnit.SECONDS)).isTrue();
        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (var in = Files.newInputStream(store)) {
            keys.load(in, password.toCharArray());
        }
        var km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        km.init(keys, password.toCharArray());
        SSLContext tls = SSLContext.getInstance("TLS");
        tls.init(km.getKeyManagers(), null, null);
        return tls;
    }

    @Test
    void theRealTestsslContainerRatesTheOwnedServerWithoutCopyingObservedValues() throws Exception {
        var server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 50);
        server.setHttpsConfigurator(new HttpsConfigurator(selfSigned()));
        server.createContext("/", e -> {
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
        });
        var executor = Executors.newFixedThreadPool(8);
        server.setExecutor(executor);
        server.start();
        int port = server.getAddress().getPort();
        Path reports = temp.resolve("security");
        try {
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(TestForgeSecurityAutoConfiguration.class))
                    .withPropertyValues(
                            "forge.security.transport.enabled=true",
                            "forge.security.transport.allowed-hosts[0]=" + HOST + ":" + port,
                            "forge.security.transport.testssl.enabled=true",
                            "forge.security.transport.testssl.test-environment=true",
                            "forge.security.transport.testssl.host-aliases[0]=" + HOST,
                            "forge.security.transport.testssl.time-budget=10m",
                            "forge.security.output-dir=" + reports)
                    .run(c -> {
                        c.getBean(SecurityTransportRunner.class).check("https://" + HOST + ":" + port);
                        var report = c.getBean(SecurityRunner.class).run();
                        List<SecurityCoverage> coverage = report.coverage();
                        assertThat(coverage)
                                .as("the container ran the whole policy")
                                .anyMatch(r -> r.check().equals("tls-testssl")
                                        && r.status().equals("measured"));
                        assertThat(coverage)
                                .filteredOn(r -> r.status().equals("measured"))
                                .extracting(SecurityCoverage::check)
                                .contains("testssl-SSLv2", "testssl-SSLv3", "testssl-TLS1",
                                        "testssl-TLS1_1", "testssl-heartbleed", "testssl-ROBOT");
                        assertThat(report.findings())
                                .extracting(f -> f.check() + " " + f.severity())
                                .as("self-signed: the JDK refuses the handshake, testssl.sh rates the chain")
                                .contains("tls-validation " + Severity.HIGH,
                                        "testssl-cert_chain_of_trust " + Severity.CRITICAL);
                        assertThat(report.findings())
                                .extracting(SecurityFinding::check)
                                .as("Java 21 offers no legacy protocol")
                                .doesNotContain("testssl-SSLv2", "testssl-SSLv3", "testssl-TLS1",
                                        "testssl-TLS1_1");
                        for (String file :
                                List.of("security.md", "security-findings.json", "security.sarif"))
                            assertThat(Files.readString(reports.resolve(file)))
                                    .doesNotContain("self signed", "TLS_AES", "ECDHE-RSA");
                    });
        } finally {
            server.stop(0);
            executor.shutdownNow();
        }
    }
}
