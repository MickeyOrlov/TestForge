package io.testforge.security.audit;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.testforge.artifact.ArtifactSink;
import io.testforge.security.audit.SecurityAuditExecution.Attempt;
import io.testforge.security.audit.SecurityAuditExecution.Outcome;
import io.testforge.security.audit.SecurityAuditProfile.Engine;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Explicit sequential execution of trusted registered adapters with bounded local process I/O. */
public final class SecurityAuditExecutor {
    private final SecurityAuditProperties properties;
    private final SecurityAuditExecutionProperties execution;
    private final SecurityAuditResultsProperties results;
    private final SecurityAuditRunner runner;
    private final SecurityAuditImports imports;
    private final ArtifactSink sink;
    private final Map<Engine, SecurityAuditAdapter> adapters;
    private final SecurityAuditExecutionContext.Secrets secrets;

    public SecurityAuditExecutor(
            SecurityAuditProperties properties,
            SecurityAuditExecutionProperties execution,
            SecurityAuditResultsProperties results,
            Collection<SecurityAuditAdapter> adapters,
            SecurityAuditExecutionContext.Secrets secrets,
            ArtifactSink sink) {
        this.properties = properties;
        this.execution = execution;
        this.results = results;
        this.secrets = secrets;
        this.sink = sink;
        this.runner = new SecurityAuditRunner(properties, results, sink);
        this.imports = new SecurityAuditImports(results);
        var registry = new HashMap<Engine, SecurityAuditAdapter>();
        for (var adapter : adapters) {
            new SecurityAuditProfile.EnginePolicy(adapter.engine(), true, adapter.version());
            if (registry.put(adapter.engine(), adapter) != null)
                throw SecurityAuditProfile.invalid("duplicate audit adapter registration");
        }
        this.adapters = Map.copyOf(registry);
    }

    public SecurityAuditExecution run(SecurityAuditProfile profile) {
        return run(profile, new SecurityAuditCancellation());
    }

    public SecurityAuditExecution run(
            SecurityAuditProfile profile, SecurityAuditCancellation cancellation) {
        if (!execution.enabled())
            throw SecurityAuditProfile.invalid(
                    "forge.security-audit.execution.enabled must be true");
        var plan = new SecurityAuditPlanner(properties).plan(profile);
        var interrupted = new AtomicBoolean(Thread.interrupted());
        if (interrupted.get()) cancellation.cancel();
        long deadline =
                System.nanoTime() + TimeUnit.SECONDS.toNanos(profile.budget().timeoutSeconds());
        var attempts = new ArrayList<Attempt>();
        var evidence = new ArrayList<SecurityAuditEvidence>();
        boolean prerequisites = plan.runnable() && preflight(profile, plan);
        try {
            for (var job : plan.jobs()) {
                if (job.status() != SecurityAuditPlan.Status.PLANNED) continue;
                long startedAt = System.nanoTime();
                var adapter = adapters.get(job.engine());
                Outcome refused =
                        !prerequisites
                                ? Outcome.PREREQUISITE_FAILED
                                : cancellation.cancelled()
                                        ? Outcome.CANCELLED
                                        : deadline - System.nanoTime() <= 0
                                                ? Outcome.BUDGET_EXHAUSTED
                                                : adapter == null
                                                        ? Outcome.ADAPTER_MISSING
                                                        : !job.version().equals(adapter.version())
                                                                ? Outcome.VERSION_MISMATCH
                                                                : null;
                if (refused != null) {
                    attempts.add(
                            new Attempt(
                                    job.asset(), job.engine(), refused, false, null, 0, 0, true));
                    continue;
                }
                var asset =
                        profile.assets().stream()
                                .filter(a -> a.id().equals(job.asset()))
                                .findFirst()
                                .orElseThrow();
                Path directory = null;
                SecurityAuditCommand command = null;
                var abandoned = new AtomicBoolean();
                var slot = new AtomicReference<SecurityAuditCommand>();
                SecurityAuditExecutionContext context = null;
                Thread preparation = null;
                Outcome outcome = Outcome.ADAPTER_FAILED;
                boolean started = false, stopped = true;
                Integer exit = null;
                long outputBytes = 0;
                SecurityAuditEvidence imported = null;
                try {
                    directory = SecurityAuditFiles.directory(execution.workDir(), "job-");
                    context =
                            new SecurityAuditExecutionContext(
                                    asset, directory, deadline, cancellation);
                    var local = context;
                    var ready = new CompletableFuture<SecurityAuditCommand>();
                    preparation =
                            Thread.ofVirtual()
                                    .start(
                                            () -> {
                                                try {
                                                    if (asset.credentialRef() != null) {
                                                        byte[] resolved =
                                                                secrets == null
                                                                        ? null
                                                                        : secrets.resolve(
                                                                                asset
                                                                                        .credentialRef());
                                                        try {
                                                            local.install(resolved);
                                                        } finally {
                                                            if (resolved != null)
                                                                Arrays.fill(resolved, (byte) 0);
                                                        }
                                                    }
                                                    SecurityAuditCommand prepared =
                                                            adapter.prepare(local);
                                                    slot.set(prepared);
                                                    if (abandoned.get()
                                                            && slot.compareAndSet(prepared, null)
                                                            && prepared != null) prepared.close();
                                                    ready.complete(prepared);
                                                } catch (Throwable e) {
                                                    ready.completeExceptionally(
                                                            new IllegalStateException(
                                                                    "audit adapter preparation"
                                                                            + " failed"));
                                                }
                                            });
                    while (command == null) {
                        if (cancellation.cancelled()) {
                            outcome = Outcome.CANCELLED;
                            break;
                        }
                        if (context.remainingNanos() == 0) {
                            outcome = Outcome.TIMED_OUT;
                            break;
                        }
                        try {
                            command =
                                    ready.get(
                                            Math.min(
                                                    context.remainingNanos(),
                                                    TimeUnit.MILLISECONDS.toNanos(25)),
                                            TimeUnit.NANOSECONDS);
                            if (command == null) break;
                        } catch (java.util.concurrent.TimeoutException e) {
                            /* Poll cancellation within the overall deadline. */
                        } catch (InterruptedException e) {
                            interrupted.set(true);
                            cancellation.cancel();
                            outcome = Outcome.CANCELLED;
                            break;
                        } catch (Exception e) {
                            outcome = Outcome.ADAPTER_FAILED;
                            break;
                        }
                    }
                    if (command != null) {
                        var result =
                                SecurityAuditProcesses.run(
                                        command, context, execution, cancellation, interrupted);
                        outcome = result.outcome();
                        started = result.started();
                        exit = result.exitCode();
                        outputBytes = result.outputBytes();
                        stopped = result.stopped();
                        if (outcome == Outcome.SUCCESS) {
                            imported =
                                    imports.normalizedReport(
                                            job.asset(),
                                            job.engine(),
                                            job.version(),
                                            result.stdout(),
                                            command.targetAliases());
                            if (imported.failure() != null) outcome = Outcome.INVALID_OUTPUT;
                            Arrays.fill(result.stdout(), (byte) 0);
                        }
                    }
                } catch (Exception e) {
                    outcome = Outcome.ADAPTER_FAILED;
                } finally {
                    abandoned.set(true);
                    if (context != null) context.close();
                    if (preparation != null) {
                        preparation.interrupt();
                        try {
                            preparation.join(execution.cleanupSeconds() * 1000L);
                        } catch (InterruptedException e) {
                            interrupted.set(true);
                        }
                        if (preparation.isAlive()) stopped = false;
                    }
                    var unused = slot.getAndSet(null);
                    if (unused != null && unused != command) unused.close();
                    if (command != null) command.close();
                    if (directory != null && stopped && !SecurityAuditFiles.remove(directory))
                        stopped = false;
                    if (!stopped) outcome = Outcome.CLEANUP_FAILED;
                }
                if (outcome == Outcome.SUCCESS && imported != null) evidence.add(imported);
                else
                    evidence.add(
                            new SecurityAuditEvidence(
                                    job.asset(),
                                    job.engine(),
                                    job.version(),
                                    null,
                                    "audit process did not complete"));
                attempts.add(
                        new Attempt(
                                job.asset(),
                                job.engine(),
                                outcome,
                                started,
                                exit,
                                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt),
                                outputBytes,
                                stopped));
            }
            var assessment = runner.run(plan, evidence);
            var receipt =
                    new SecurityAuditExecution(1, "PRIVATE_IO_NO_OS_SANDBOX", attempts, assessment);
            byte[] json =
                    new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsBytes(receipt);
            if (json.length > results.maxBytes())
                throw SecurityAuditProfile.invalid("audit execution receipt exceeds byte bound");
            Path directory = SecurityAuditFiles.directory(results.outputDir(), "execution-");
            Path file = directory.resolve("security-audit-execution.json");
            Files.write(file, json, java.nio.file.StandardOpenOption.CREATE_NEW);
            try {
                sink.registerFile(
                        "module-security-audit",
                        "security-audit-execution",
                        file.getFileName().toString(),
                        file,
                        "application/json",
                        Map.of(
                                "formatVersion",
                                "1",
                                "healthy",
                                Boolean.toString(assessment.healthy())));
            } catch (RuntimeException ignored) {
                /* Artifact registration is best-effort. */
            }
            return receipt;
        } catch (java.io.IOException e) {
            throw new IllegalStateException("audit execution receipt could not be written");
        } finally {
            if (interrupted.get()) Thread.currentThread().interrupt();
        }
    }

    private static boolean preflight(SecurityAuditProfile profile, SecurityAuditPlan plan) {
        try {
            var active =
                    plan.jobs().stream()
                            .filter(j -> j.status() == SecurityAuditPlan.Status.PLANNED)
                            .map(SecurityAuditPlan.Job::asset)
                            .collect(java.util.stream.Collectors.toSet());
            for (var asset : profile.assets()) {
                if (!active.contains(asset.id())) continue;
                if (asset.openApi() != null) {
                    Path file = Path.of(asset.openApi());
                    SecurityAuditFiles.noLinks(file);
                    if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                            || Files.size(file) > 4194304) return false;
                }
                if (asset.kind() == SecurityAuditProfile.TargetKind.REPOSITORY) {
                    Path path = Path.of(asset.location());
                    SecurityAuditFiles.noLinks(path);
                    if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) return false;
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
