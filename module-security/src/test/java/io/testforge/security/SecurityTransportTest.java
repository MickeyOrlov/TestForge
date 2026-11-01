package io.testforge.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.in;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;

class SecurityTransportTest {
    @TempDir static Path temp;
    static SSLContext trusted;
    static SSLServerSocket server;
    static Thread worker;

    @BeforeAll
    static void tlsFixture() throws Exception {
        String password = UUID.randomUUID().toString();
        Path store = temp.resolve("fixture.p12");
        var command =
                List.of(
                        Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                        "-genkeypair",
                        "-alias",
                        "fixture",
                        "-keyalg",
                        "RSA",
                        "-keysize",
                        "2048",
                        "-dname",
                        "CN=localhost",
                        "-ext",
                        "SAN=dns:localhost",
                        "-validity",
                        "2",
                        "-storetype",
                        "PKCS12",
                        "-keystore",
                        store.toString(),
                        "-storepass",
                        password,
                        "-keypass",
                        password,
                        "-noprompt");
        Process process =
                new ProcessBuilder(command)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .start();
        assertThat(process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue()).isZero();
        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (var in = Files.newInputStream(store)) {
            keys.load(in, password.toCharArray());
        }
        var km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        km.init(keys, password.toCharArray());
        var tm = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tm.init(keys);
        trusted = SSLContext.getInstance("TLS");
        trusted.init(km.getKeyManagers(), tm.getTrustManagers(), new SecureRandom());
        server =
                (SSLServerSocket)
                        trusted.getServerSocketFactory()
                                .createServerSocket(0, 10, InetAddress.getByName("127.0.0.1"));
        worker =
                new Thread(
                        () -> {
                            while (!server.isClosed())
                                try (var socket = (SSLSocket) server.accept()) {
                                    socket.setSoTimeout(2000);
                                    socket.startHandshake();
                                } catch (Exception ignored) {
                                }
                        },
                        "security-tls-fixture");
        worker.setDaemon(true);
        worker.start();
    }

    @AfterAll
    static void stop() throws Exception {
        server.close();
        worker.join(2000);
    }

    SecurityTransportProperties properties(boolean legacy, boolean weak) {
        return new SecurityTransportProperties(
                true, List.of("localhost", "127.0.0.1"), 30, Duration.ofSeconds(2), legacy, weak);
    }

    @Test
    void trustedTlsWarnsAboutExpiryAndReportsLocalProbeLimits() {
        var collector = new SecurityCollector(100);
        new SecurityTransportRunner(properties(true, true), collector, trusted, Clock.systemUTC())
                .check("https://localhost:" + server.getLocalPort());
        assertThat(collector.findings())
                .extracting(SecurityFinding::check)
                .contains("tls-expiry-window")
                .doesNotContain("tls-validation");
        assertThat(collector.coverage())
                .anyMatch(
                        c -> c.status().equals("not_measured") && c.reason().contains("local JDK"));
        assertThat(collector.findings().toString()).doesNotContain("CN=localhost");
    }

    @Test
    void hostnameAndTrustFailuresBecomeFindingsRatherThanTrustAllConnections() {
        var hostname = new SecurityCollector(100);
        new SecurityTransportRunner(properties(false, false), hostname, trusted, Clock.systemUTC())
                .check("https://127.0.0.1:" + server.getLocalPort());
        assertThat(hostname.findings())
                .extracting(SecurityFinding::check)
                .contains("tls-validation");
        var trust = new SecurityCollector(100);
        new SecurityTransportRunner(properties(false, false), trust)
                .check("https://localhost:" + server.getLocalPort());
        assertThat(trust.findings()).extracting(SecurityFinding::check).contains("tls-validation");
    }

    @Test
    void assessmentTimeAndAllowlistAreApplied() {
        var collector = new SecurityCollector(100);
        var runner =
                new SecurityTransportRunner(
                        properties(false, false),
                        collector,
                        trusted,
                        Clock.offset(Clock.systemUTC(), Duration.ofDays(5)));
        runner.check("https://localhost:" + server.getLocalPort());
        assertThat(collector.findings())
                .extracting(SecurityFinding::check)
                .contains("tls-validation");
        assertThatThrownBy(() -> runner.check("https://not-allowed.example.test"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void redirectProbeDoesNotFollowAnExternalLocation() throws Exception {
        var http =
                com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext(
                "/",
                exchange -> {
                    exchange.getResponseHeaders().add("Location", "https://outside.example.test/");
                    exchange.sendResponseHeaders(302, -1);
                    exchange.close();
                });
        http.start();
        try {
            var c = new SecurityCollector(100);
            new SecurityTransportRunner(properties(false, false), c)
                    .check("http://127.0.0.1:" + http.getAddress().getPort());
            assertThat(c.findings()).extracting(SecurityFinding::check).contains("https-redirect");
        } finally {
            http.stop(0);
        }
    }
}
