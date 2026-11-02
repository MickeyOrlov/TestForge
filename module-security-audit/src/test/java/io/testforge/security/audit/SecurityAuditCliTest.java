package io.testforge.security.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

class SecurityAuditCliTest {
    @TempDir Path dir;
    Path profile;

    @BeforeEach
    void setup() throws Exception {
        dir = dir.toRealPath();
        profile = dir.resolve("profile.json");
        Files.writeString(
                profile,
                """
{"id":"internal","environment":"LOCAL","allowedHosts":["127.0.0.1"],
"assets":[{"id":"api","layer":"APPLICATION","kind":"URL","location":"http://127.0.0.1"}],
"engines":[{"engine":"TESTFORGE","enabled":true,"version":"1.0.0"}],
"budget":{"maxRequests":10,"timeoutSeconds":10,"maxConcurrent":1}}
""");
    }

    SecurityAuditCli cli(String mode, AtomicInteger calls) {
        var adapter =
                new SecurityAuditAdapter() {
                    public SecurityAuditProfile.Engine engine() {
                        return SecurityAuditProfile.Engine.TESTFORGE;
                    }

                    public String version() {
                        return "1.0.0";
                    }

                    public SecurityAuditCommand prepare(SecurityAuditExecutionContext context)
                            throws Exception {
                        calls.incrementAndGet();
                        return new SecurityAuditCommand(
                                Path.of(System.getProperty("java.home"), "bin", "java")
                                        .toRealPath(),
                                List.of(
                                        "-cp",
                                        Path.of(
                                                        AuditProcessFixture.class
                                                                .getProtectionDomain()
                                                                .getCodeSource()
                                                                .getLocation()
                                                                .toURI())
                                                .toRealPath()
                                                .toString(),
                                        AuditProcessFixture.class.getName(),
                                        mode),
                                null,
                                Map.of("GET /fixture", "fixture"),
                                Set.of(0, 2));
                    }
                };
        return new SecurityAuditCli(List.of(adapter), null);
    }

    @Test
    void planNeverPreparesAdaptersAndWritesOnlyValueFreePlan() throws Exception {
        var calls = new AtomicInteger();
        assertThat(
                        cli("OK", calls)
                                .execute(
                                        "plan",
                                        "--profile",
                                        profile.toString(),
                                        "--output",
                                        dir.resolve("plans").toString()))
                .isZero();
        assertThat(calls).hasValue(0);
        try (var files = Files.walk(dir.resolve("plans"))) {
            for (var file : files.filter(Files::isRegularFile).toList()) {
                var json = new ObjectMapper().readTree(Files.readAllBytes(file));
                assertThat(json.path("executed").booleanValue()).isFalse();
                assertThat(Files.readString(file)).doesNotContain("127.0.0.1", profile.toString());
            }
        }
    }

    @Test
    void runRequiresExplicitOptInAndEmptyDefaultRegistryCannotPretendToScan() {
        var calls = new AtomicInteger();
        assertThat(
                        cli("OK", calls)
                                .execute(
                                        "run",
                                        "--profile",
                                        profile.toString(),
                                        "--output",
                                        dir.resolve("refused").toString()))
                .isEqualTo(3);
        assertThat(calls).hasValue(0);
        assertThat(Files.exists(dir.resolve("refused"))).isFalse();
        assertThat(
                        new SecurityAuditCli()
                                .execute(
                                        "run",
                                        "--enable-run",
                                        "--profile",
                                        profile.toString(),
                                        "--output",
                                        dir.resolve("missing").toString()))
                .isEqualTo(3);
    }

    @Test
    void exitCodesDistinguishCompleteFindingsFromExecutionFailure() {
        assertThat(
                        cli("OK", new AtomicInteger())
                                .execute(
                                        "run",
                                        "--enable-run",
                                        "--profile",
                                        profile.toString(),
                                        "--output",
                                        dir.resolve("good").toString()))
                .isZero();
        assertThat(
                        cli("VULN", new AtomicInteger())
                                .execute(
                                        "run",
                                        "--enable-run",
                                        "--profile",
                                        profile.toString(),
                                        "--output",
                                        dir.resolve("vuln").toString()))
                .isEqualTo(2);
        assertThat(
                        cli("BAD", new AtomicInteger())
                                .execute(
                                        "run",
                                        "--enable-run",
                                        "--profile",
                                        profile.toString(),
                                        "--output",
                                        dir.resolve("bad").toString()))
                .isEqualTo(3);
    }

    @Test
    void unknownDuplicateArgumentsAndSymlinkProfileCannotTriggerExecution() throws Exception {
        var calls = new AtomicInteger();
        var cli = cli("OK", calls);
        String output = dir.resolve("output").toString();
        assertThat(
                        cli.execute(
                                "plan",
                                "--profile",
                                profile.toString(),
                                "--profile",
                                profile.toString(),
                                "--output",
                                output))
                .isEqualTo(3);
        assertThat(
                        cli.execute(
                                "run",
                                "--enable-run",
                                "--command",
                                "private-shell-command",
                                "--profile",
                                profile.toString(),
                                "--output",
                                output))
                .isEqualTo(3);
        Path link = dir.resolve("linked.json");
        Files.createSymbolicLink(link, profile);
        assertThat(
                        cli.execute(
                                "run",
                                "--enable-run",
                                "--profile",
                                link.toString(),
                                "--output",
                                output))
                .isEqualTo(3);
        assertThat(calls).hasValue(0);
        assertThat(Files.exists(Path.of(output))).isFalse();
    }
}
