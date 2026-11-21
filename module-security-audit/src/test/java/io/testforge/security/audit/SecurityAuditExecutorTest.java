package io.testforge.security.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.testforge.artifact.ArtifactSink;
import io.testforge.security.audit.SecurityAuditExecution.Outcome;
import io.testforge.security.audit.SecurityAuditProfile.Asset;
import io.testforge.security.audit.SecurityAuditProfile.Budget;
import io.testforge.security.audit.SecurityAuditProfile.Engine;
import io.testforge.security.audit.SecurityAuditProfile.EnginePolicy;
import io.testforge.security.audit.SecurityAuditProfile.Environment;
import io.testforge.security.audit.SecurityAuditProfile.Layer;
import io.testforge.security.audit.SecurityAuditProfile.TargetKind;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

class SecurityAuditExecutorTest {
    @TempDir Path dir;

    @BeforeEach
    void canonical() throws Exception {
        dir = dir.toRealPath();
    }

    final SecurityAuditProperties properties =
            new SecurityAuditProperties(true, null, null, null, null, null);

    SecurityAuditProfile profile(int seconds, String credential, int assets) {
        var list = new ArrayList<Asset>();
        for (int i = 0; i < assets; i++)
            list.add(
                    new Asset(
                            "api" + i,
                            Layer.APPLICATION,
                            TargetKind.URL,
                            "http://127.0.0.1",
                            null,
                            null,
                            credential));
        return new SecurityAuditProfile(
                "internal",
                Environment.LOCAL,
                Set.of("127.0.0.1"),
                list,
                List.of(new EnginePolicy(Engine.TESTFORGE, true, "1.0.0")),
                new Budget(10, seconds, 2));
    }

    interface Prepare {
        SecurityAuditCommand apply(SecurityAuditExecutionContext context) throws Exception;
    }

    SecurityAuditAdapter adapter(Prepare prepare) {
        return new SecurityAuditAdapter() {
            public Engine engine() {
                return Engine.TESTFORGE;
            }

            public String version() {
                return "1.0.0";
            }

            public SecurityAuditCommand prepare(SecurityAuditExecutionContext context)
                    throws Exception {
                return prepare.apply(context);
            }
        };
    }

    SecurityAuditCommand command(
            String mode,
            SecurityAuditExecutionContext context,
            byte[] input,
            Set<Integer> exitCodes)
            throws Exception {
        return new SecurityAuditCommand(
                Path.of(System.getProperty("java.home"), "bin", "java").toRealPath(),
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
                input,
                Map.of("GET /fixture", "fixture"),
                exitCodes);
    }

    SecurityAuditExecutor executor(
            List<SecurityAuditAdapter> adapters,
            int output,
            SecurityAuditExecutionContext.Secrets secrets) {
        return new SecurityAuditExecutor(
                properties,
                new SecurityAuditExecutionProperties(
                        true, dir.resolve("work").toString(), output, 1, null),
                new SecurityAuditResultsProperties(dir.resolve("reports").toString(), null, null),
                adapters,
                secrets,
                ArtifactSink.NO_OP);
    }

    SecurityAuditExecution execute(String mode, int seconds, int output) {
        return executor(
                        List.of(adapter(c -> command(mode, c, new byte[0], Set.of(0)))),
                        output,
                        null)
                .run(profile(seconds, null, 1));
    }

    @Test
    void successfulProcessHasPrivateEnvironmentBoundedEvidenceAndNoWorkspaceResidue()
            throws Exception {
        var result = execute("OK", 10, 65536);
        assertThat(result.assessment().healthy()).isTrue();
        assertThat(result.attempts().getFirst().outcome()).isEqualTo(Outcome.SUCCESS);
        assertThat(result.attempts().getFirst().cleanupComplete()).isTrue();
        try (var paths = Files.list(dir.resolve("work"))) {
            assertThat(paths).isEmpty();
        }
        try (var paths = Files.walk(dir.resolve("reports"))) {
            for (var file : paths.filter(Files::isRegularFile).toList())
                assertThat(Files.readString(file))
                        .doesNotContain("127.0.0.1", "synthetic-private-scratch", "-cp");
        }
    }

    @Test
    void findingsExitPolicyCanBeDeclaredByTrustedAdapterWithoutTreatingThemAsCrashes() {
        var result =
                executor(
                                List.of(
                                        adapter(
                                                c ->
                                                        command(
                                                                "VULN",
                                                                c,
                                                                new byte[0],
                                                                Set.of(0, 2)))),
                                65536,
                                null)
                        .run(profile(10, null, 1));
        assertThat(result.attempts().getFirst().outcome()).isEqualTo(Outcome.SUCCESS);
        assertThat(result.assessment().complete()).isTrue();
        assertThat(result.assessment().healthy()).isFalse();
        assertThat(
                        result.assessment().jobs().stream()
                                .filter(j -> j.security() != null)
                                .findFirst()
                                .orElseThrow()
                                .security()
                                .path("findings"))
                .hasSize(1);
    }

    @Test
    void nonzeroMalformedAndBothOutputFloodsAreIncompleteAndNeverExposeRawConsole() {
        for (String mode : List.of("NONZERO", "BAD", "FLOOD_OUT", "FLOOD_ERR")) {
            var result = execute(mode, 10, mode.startsWith("FLOOD") ? 256 : 65536);
            assertThat(result.assessment().healthy()).isFalse();
            assertThat(result.attempts().getFirst().outcome())
                    .isEqualTo(
                            mode.equals("NONZERO")
                                    ? Outcome.NONZERO_EXIT
                                    : mode.equals("BAD")
                                            ? Outcome.INVALID_OUTPUT
                                            : Outcome.OUTPUT_LIMIT);
        }
    }

    @Test
    void timeoutConsumesTheOverallBudgetAndNeverStartsTheNextJob() {
        var calls = new AtomicInteger();
        var adapter =
                adapter(
                        c -> {
                            calls.incrementAndGet();
                            return command("HANG", c, new byte[0], Set.of(0));
                        });
        var result = executor(List.of(adapter), 65536, null).run(profile(1, null, 2));
        assertThat(result.attempts().get(0).outcome()).isEqualTo(Outcome.TIMED_OUT);
        assertThat(result.attempts().get(1).outcome()).isEqualTo(Outcome.BUDGET_EXHAUSTED);
        assertThat(calls).hasValue(1);
        assertThat(result.assessment().complete()).isFalse();
    }

    @Test
    void observedChildProcessesAreStoppedBeforeWorkspaceRemoval() {
        var result = execute("CHILD", 2, 65536);
        assertThat(result.attempts().getFirst().outcome()).isEqualTo(Outcome.TIMED_OUT);
        assertThat(result.attempts().getFirst().cleanupComplete()).isTrue();
    }

    @Test
    void cancellationDuringPreparationIsPromptAndDoesNotLaunchAProcess() throws Exception {
        var ready = new CountDownLatch(1);
        var cancel = new SecurityAuditCancellation();
        var result = new AtomicReference<SecurityAuditExecution>();
        var executor =
                executor(
                        List.of(
                                adapter(
                                        c -> {
                                            ready.countDown();
                                            new CountDownLatch(1).await();
                                            return null;
                                        })),
                        65536,
                        null);
        Thread worker =
                Thread.ofVirtual()
                        .start(() -> result.set(executor.run(profile(30, null, 1), cancel)));
        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        cancel.cancel();
        worker.join(5000);
        assertThat(worker.isAlive()).isFalse();
        assertThat(result.get().attempts().getFirst().outcome()).isEqualTo(Outcome.CANCELLED);
        assertThat(result.get().attempts().getFirst().started()).isFalse();
    }

    @Test
    void interruptionStopsAnOwnedProcessAndRestoresCallerInterruptStatus() throws Exception {
        var result = new AtomicReference<SecurityAuditExecution>();
        var interrupted = new AtomicReference<Boolean>();
        var prepared = new CountDownLatch(1);
        var executor =
                executor(
                        List.of(
                                adapter(
                                        c -> {
                                            prepared.countDown();
                                            return command("HANG", c, new byte[0], Set.of(0));
                                        })),
                        65536,
                        null);
        Thread worker =
                Thread.ofVirtual()
                        .start(
                                () -> {
                                    result.set(executor.run(profile(30, null, 1)));
                                    interrupted.set(Thread.currentThread().isInterrupted());
                                });
        assertThat(prepared.await(5, TimeUnit.SECONDS)).isTrue();
        org.awaitility.Awaitility.await()
                .atMost(java.time.Duration.ofSeconds(5))
                .until(
                        () -> {
                            try (var paths = Files.walk(dir.resolve("work"))) {
                                return paths.anyMatch(
                                        p -> p.getFileName().toString().equals("started.pid"));
                            }
                        });
        worker.interrupt();
        worker.join(5000);
        assertThat(worker.isAlive()).isFalse();
        assertThat(interrupted.get()).isTrue();
        assertThat(result.get().attempts().getFirst().outcome()).isEqualTo(Outcome.CANCELLED);
    }

    @Test
    void secretMaterialIsOnlyStdinAndLeakageInArgsOrStdoutBlocksResults() throws Exception {
        String secret = "synthetic-credential-123456789";
        byte[] original = secret.getBytes(StandardCharsets.UTF_8);
        var source =
                executor(
                        List.of(adapter(c -> command("STDERR", c, c.credential(), Set.of(0)))),
                        65536,
                        ref -> original);
        var good = source.run(profile(10, "owner", 1));
        assertThat(good.assessment().healthy()).isTrue();
        assertThat(original).containsOnly((byte) 0);
        var leak =
                executor(
                                List.of(
                                        adapter(
                                                c ->
                                                        command(
                                                                "SECRET",
                                                                c,
                                                                c.credential(),
                                                                Set.of(0)))),
                                65536,
                                ref -> secret.getBytes(StandardCharsets.UTF_8))
                        .run(profile(10, "owner", 1));
        assertThat(leak.attempts().getFirst().outcome()).isEqualTo(Outcome.SECRET_LEAK);
        var args =
                executor(
                                List.of(
                                        adapter(
                                                c ->
                                                        new SecurityAuditCommand(
                                                                Path.of(
                                                                                System.getProperty(
                                                                                        "java.home"),
                                                                                "bin",
                                                                                "java")
                                                                        .toRealPath(),
                                                                List.of(secret),
                                                                new byte[0],
                                                                Map.of()))),
                                65536,
                                ref -> secret.getBytes(StandardCharsets.UTF_8))
                        .run(profile(10, "owner", 1));
        assertThat(args.attempts().getFirst().outcome()).isEqualTo(Outcome.SECRET_LEAK);
        assertThat(args.attempts().getFirst().started()).isFalse();
        try (var paths = Files.walk(dir.resolve("reports"))) {
            for (var file : paths.filter(Files::isRegularFile).toList())
                assertThat(Files.readString(file)).doesNotContain(secret);
        }
    }

    @Test
    void missingAdapterMissingBinaryAndUnresolvedCredentialsFailClosed() {
        assertThat(
                        executor(List.of(), 65536, null)
                                .run(profile(10, null, 1))
                                .attempts()
                                .getFirst()
                                .outcome())
                .isEqualTo(Outcome.ADAPTER_MISSING);
        var missing =
                executor(
                                List.of(
                                        adapter(
                                                c ->
                                                        new SecurityAuditCommand(
                                                                dir.resolve("missing"),
                                                                List.of(),
                                                                null,
                                                                Map.of()))),
                                65536,
                                null)
                        .run(profile(10, null, 1));
        assertThat(missing.attempts().getFirst().outcome()).isEqualTo(Outcome.START_FAILED);
        assertThat(
                        executor(
                                        List.of(adapter(c -> command("OK", c, null, Set.of(0)))),
                                        65536,
                                        null)
                                .run(profile(10, "owner", 1))
                                .attempts()
                                .getFirst()
                                .outcome())
                .isEqualTo(Outcome.ADAPTER_FAILED);
    }

    @Test
    void allowlistAndLocalInputRefusalPrecedeAdapterOrCredentialAccess() {
        var calls = new AtomicInteger();
        var adapter =
                adapter(
                        c -> {
                            calls.incrementAndGet();
                            return command("OK", c, null, Set.of(0));
                        });
        var executor =
                executor(
                        List.of(adapter),
                        65536,
                        ref -> {
                            calls.incrementAndGet();
                            return new byte[1];
                        });
        var original = profile(10, "owner", 1);
        var outside =
                new SecurityAuditProfile(
                        original.id(),
                        original.environment(),
                        Set.of(),
                        original.assets(),
                        original.engines(),
                        original.budget());
        assertThat(executor.run(outside).assessment().healthy()).isFalse();
        assertThat(calls).hasValue(0);
        var missing =
                new Asset(
                        "api0",
                        Layer.APPLICATION,
                        TargetKind.URL,
                        "http://127.0.0.1",
                        null,
                        dir.resolve("missing.json").toString(),
                        "owner");
        var invalid =
                new SecurityAuditProfile(
                        original.id(),
                        original.environment(),
                        original.allowedHosts(),
                        List.of(missing),
                        original.engines(),
                        original.budget());
        assertThat(executor.run(invalid).attempts().getFirst().outcome())
                .isEqualTo(Outcome.PREREQUISITE_FAILED);
        assertThat(calls).hasValue(0);
    }

    @Test
    void disabledExecutorAndDuplicateRegistrationCannotStartAnything() {
        var off =
                new SecurityAuditExecutor(
                        properties,
                        new SecurityAuditExecutionProperties(false, null, null, null, null),
                        new SecurityAuditResultsProperties(null, null, null),
                        List.of(),
                        null,
                        ArtifactSink.NO_OP);
        assertThatThrownBy(() -> off.run(profile(10, null, 1)))
                .hasMessageContaining("execution.enabled");
        var adapter = adapter(c -> null);
        assertThatThrownBy(() -> executor(List.of(adapter, adapter), 65536, null))
                .hasMessageContaining("duplicate");
    }

    @Test
    void observedProcessLimitAndVersionMismatchAreExplicit() {
        var adapter = adapter(c -> command("CHILDREN", c, new byte[0], Set.of(0)));
        var executor =
                new SecurityAuditExecutor(
                        properties,
                        new SecurityAuditExecutionProperties(
                                true, dir.resolve("work").toString(), 65536, 1, 1),
                        new SecurityAuditResultsProperties(
                                dir.resolve("reports").toString(), null, null),
                        List.of(adapter),
                        null,
                        ArtifactSink.NO_OP);
        var receipt = executor.run(profile(10, null, 1));
        assertThat(receipt.attempts().getFirst().outcome()).isEqualTo(Outcome.PROCESS_LIMIT);
        assertThat(receipt.attempts().getFirst().cleanupComplete()).isTrue();
        var wrong =
                new SecurityAuditAdapter() {
                    public Engine engine() {
                        return Engine.TESTFORGE;
                    }

                    public String version() {
                        return "2.0.0";
                    }

                    public SecurityAuditCommand prepare(SecurityAuditExecutionContext context) {
                        throw new AssertionError("must not prepare");
                    }
                };
        assertThat(
                        executor(List.of(wrong), 65536, null)
                                .run(profile(10, null, 1))
                                .attempts()
                                .getFirst()
                                .outcome())
                .isEqualTo(Outcome.VERSION_MISMATCH);
    }

    @Test
    void uncooperativePreparationIsIncompleteAndCannotLaunchALateCommand() throws Exception {
        var release = new CountDownLatch(1);
        var returned = new AtomicReference<SecurityAuditCommand>();
        var executor =
                executor(
                        List.of(
                                adapter(
                                        c -> {
                                            while (release.getCount() != 0)
                                                try {
                                                    release.await();
                                                } catch (InterruptedException ignored) {
                                                }
                                            var value =
                                                    command(
                                                            "OK",
                                                            c,
                                                            "private-input"
                                                                    .getBytes(
                                                                            StandardCharsets.UTF_8),
                                                            Set.of(0));
                                            returned.set(value);
                                            return value;
                                        })),
                        65536,
                        null);
        try {
            var receipt = executor.run(profile(1, null, 1));
            assertThat(receipt.attempts().getFirst().outcome()).isEqualTo(Outcome.CLEANUP_FAILED);
            assertThat(receipt.attempts().getFirst().started()).isFalse();
            assertThat(receipt.assessment().complete()).isFalse();
        } finally {
            release.countDown();
        }
        org.awaitility.Awaitility.await()
                .atMost(java.time.Duration.ofSeconds(5))
                .until(
                        () ->
                                returned.get() != null
                                        && java.util.Arrays.equals(
                                                returned.get().input(),
                                                new byte["private-input".length()]));
    }

    @Test
    void executionReceiptMatchesItsSchemaAndSecretAliasesCannotReachReports() throws Exception {
        var receipt = execute("OK", 10, 65536);
        var schema =
                com.networknt.schema.JsonSchemaFactory.getInstance(
                                com.networknt.schema.SpecVersion.VersionFlag.V7)
                        .getSchema(
                                getClass()
                                        .getResource("/security-audit-execution.schema.json")
                                        .toURI());
        assertThat(
                        schema.validate(
                                new com.fasterxml.jackson.databind.ObjectMapper()
                                        .valueToTree(receipt)))
                .isEmpty();
        String secret = "synthetic-credential-123456789";
        var unsafe =
                executor(
                                List.of(
                                        adapter(
                                                c -> {
                                                    var basic = command("OK", c, null, Set.of(0));
                                                    return new SecurityAuditCommand(
                                                            basic.executable(),
                                                            basic.arguments(),
                                                            null,
                                                            Map.of("GET /fixture", secret));
                                                })),
                                65536,
                                ref -> secret.getBytes(StandardCharsets.UTF_8))
                        .run(profile(10, "owner", 1));
        assertThat(unsafe.attempts().getFirst().outcome()).isEqualTo(Outcome.SECRET_LEAK);
        assertThat(unsafe.attempts().getFirst().started()).isFalse();
    }

    @Test
    void adapterDiagnosticErrorsAreSanitizedInsteadOfLeakingFromThePreparationThread()
            throws Exception {
        var receipt =
                executor(
                                List.of(
                                        adapter(
                                                c -> {
                                                    throw new AssertionError(
                                                            "private-adapter-diagnostic");
                                                })),
                                65536,
                                null)
                        .run(profile(10, null, 1));
        assertThat(receipt.attempts().getFirst().outcome()).isEqualTo(Outcome.ADAPTER_FAILED);
        assertThat(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(receipt))
                .doesNotContain("private-adapter-diagnostic");
    }
}
