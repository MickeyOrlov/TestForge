package io.testforge.security.audit;

import io.testforge.security.audit.SecurityAuditExecution.Outcome;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Portable private-I/O process boundary. Does not provide kernel/filesystem/network confinement.
 */
final class SecurityAuditProcesses {
    record Result(
            Outcome outcome,
            boolean started,
            Integer exitCode,
            long outputBytes,
            boolean stopped,
            byte[] stdout) {}

    static Result run(
            SecurityAuditCommand command,
            SecurityAuditExecutionContext context,
            SecurityAuditExecutionProperties properties,
            SecurityAuditCancellation cancellation,
            java.util.concurrent.atomic.AtomicBoolean interrupted) {
        Process process = null;
        Set<ProcessHandle> children = new LinkedHashSet<>();
        var issue = new AtomicReference<Outcome>();
        var count = new AtomicLong();
        var output = new ByteArrayOutputStream();
        var readers = new ArrayList<CompletableFuture<Void>>();
        byte[] input = command.input(), credential = context.credential();
        boolean stopped = true, started = false;
        Integer exit = null;
        try {
            Path executable = command.executable();
            SecurityAuditFiles.noLinks(executable);
            if (!Files.isRegularFile(executable) || !Files.isExecutable(executable))
                return new Result(Outcome.START_FAILED, false, null, 0, true, null);
            List<String> arguments = new ArrayList<>();
            arguments.add(executable.toString());
            arguments.addAll(command.arguments());
            var exposed = new ArrayList<>(arguments);
            exposed.addAll(command.targetAliases().values());
            for (String arg : exposed)
                if (contains(arg.getBytes(StandardCharsets.UTF_8), credential))
                    return new Result(Outcome.SECRET_LEAK, false, null, 0, true, null);
            if (context.cancelled())
                return new Result(
                        cancellation.cancelled() ? Outcome.CANCELLED : Outcome.BUDGET_EXHAUSTED,
                        false,
                        null,
                        0,
                        true,
                        null);
            var builder = new ProcessBuilder(arguments).directory(context.directory().toFile());
            builder.environment().clear();
            builder.environment().put("LANG", "C");
            builder.environment().put("LC_ALL", "C");
            builder.environment().put("HOME", context.directory().toString());
            builder.environment().put("TMPDIR", context.directory().toString());
            process = builder.start();
            started = true;
            Process running = process;
            readers.add(
                    pump(
                            running.getInputStream(),
                            output,
                            count,
                            properties.maxOutputBytes(),
                            issue));
            readers.add(
                    pump(
                            running.getErrorStream(),
                            null,
                            count,
                            properties.maxOutputBytes(),
                            issue));
            var inputDone = new CompletableFuture<Void>();
            Thread.ofVirtual()
                    .start(
                            () -> {
                                try (var stdin = running.getOutputStream()) {
                                    stdin.write(input);
                                    inputDone.complete(null);
                                } catch (Exception e) {
                                    inputDone.completeExceptionally(
                                            new IllegalStateException("process input failed"));
                                }
                            });
            readers.add(inputDone);
            while (process.isAlive()) {
                if (Thread.interrupted()) {
                    interrupted.set(true);
                    cancellation.cancel();
                }
                if (cancellation.cancelled()) {
                    issue.compareAndSet(null, Outcome.CANCELLED);
                    break;
                }
                if (context.remainingNanos() == 0) {
                    issue.compareAndSet(null, Outcome.TIMED_OUT);
                    break;
                }
                process.descendants().limit(properties.maxChildren() + 1L).forEach(children::add);
                if (children.size() > properties.maxChildren())
                    issue.compareAndSet(null, Outcome.PROCESS_LIMIT);
                if (issue.get() != null) break;
                try {
                    process.waitFor(
                            Math.min(context.remainingNanos(), TimeUnit.MILLISECONDS.toNanos(25)),
                            TimeUnit.NANOSECONDS);
                } catch (InterruptedException e) {
                    interrupted.set(true);
                    cancellation.cancel();
                    issue.compareAndSet(null, Outcome.CANCELLED);
                    break;
                }
            }
            if (!process.isAlive()) exit = process.exitValue();
            if (issue.get() == null && exit != null && !command.successExitCodes().contains(exit))
                issue.set(Outcome.NONZERO_EXIT);
            // Terminate observed descendants even when the direct process exited normally.
            stopped = stop(process, children, properties.cleanupSeconds(), interrupted);
            long cleanupDeadline =
                    System.nanoTime() + TimeUnit.SECONDS.toNanos(properties.cleanupSeconds());
            for (var reader : readers) {
                try {
                    reader.get(
                            Math.max(1, cleanupDeadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                } catch (InterruptedException e) {
                    interrupted.set(true);
                    cancellation.cancel();
                    issue.compareAndSet(null, Outcome.CANCELLED);
                } catch (Exception e) {
                    issue.compareAndSet(null, Outcome.INVALID_OUTPUT);
                }
            }
            if (process.isAlive()) stopped = false;
            else exit = process.exitValue();
            if (!stopped) issue.set(Outcome.CLEANUP_FAILED);
            for (var stream :
                    List.of(
                            process.getInputStream(),
                            process.getErrorStream(),
                            process.getOutputStream())) {
                var closed = new CompletableFuture<Void>();
                Thread.ofVirtual()
                        .start(
                                () -> {
                                    try {
                                        stream.close();
                                        closed.complete(null);
                                    } catch (Exception e) {
                                        closed.completeExceptionally(new IllegalStateException());
                                    }
                                });
                try {
                    closed.get(
                            Math.max(1, cleanupDeadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                } catch (InterruptedException e) {
                    interrupted.set(true);
                    stopped = false;
                } catch (Exception e) {
                    stopped = false;
                }
            }
            if (!stopped) issue.set(Outcome.CLEANUP_FAILED);
            byte[] bytes = output.toByteArray();
            if (contains(bytes, credential) || containsJsonSecret(bytes, credential))
                issue.set(Outcome.SECRET_LEAK);
            Outcome outcome = issue.get() == null ? Outcome.SUCCESS : issue.get();
            return new Result(
                    outcome,
                    true,
                    exit,
                    count.get(),
                    stopped,
                    outcome == Outcome.SUCCESS ? bytes : null);
        } catch (Exception e) {
            stopped =
                    process == null
                            || stop(process, children, properties.cleanupSeconds(), interrupted);
            return new Result(
                    !stopped
                            ? Outcome.CLEANUP_FAILED
                            : started ? Outcome.INVALID_OUTPUT : Outcome.START_FAILED,
                    started,
                    exit,
                    count.get(),
                    stopped,
                    null);
        } finally {
            if (process != null) {
                stop(process, children, properties.cleanupSeconds(), interrupted);
                Process closing = process;
                Thread.ofVirtual()
                        .start(
                                () -> {
                                    try {
                                        closing.getInputStream().close();
                                    } catch (Exception ignored) {
                                    }
                                    try {
                                        closing.getErrorStream().close();
                                    } catch (Exception ignored) {
                                    }
                                    try {
                                        closing.getOutputStream().close();
                                    } catch (Exception ignored) {
                                    }
                                });
            }
            Arrays.fill(input, (byte) 0);
            Arrays.fill(credential, (byte) 0);
        }
    }

    private static CompletableFuture<Void> pump(
            java.io.InputStream stream,
            ByteArrayOutputStream capture,
            AtomicLong total,
            int max,
            AtomicReference<Outcome> issue) {
        var done = new CompletableFuture<Void>();
        Thread.ofVirtual()
                .start(
                        () -> {
                            byte[] buffer = new byte[4096];
                            try (stream) {
                                int length;
                                while ((length = stream.read(buffer)) != -1) {
                                    long end = total.addAndGet(length);
                                    if (end > max) {
                                        issue.compareAndSet(null, Outcome.OUTPUT_LIMIT);
                                        break;
                                    }
                                    if (capture != null) capture.write(buffer, 0, length);
                                }
                                done.complete(null);
                            } catch (Exception e) {
                                done.completeExceptionally(
                                        new IllegalStateException("process output failed"));
                            } finally {
                                Arrays.fill(buffer, (byte) 0);
                            }
                        });
        return done;
    }

    private static boolean stop(
            Process process,
            Set<ProcessHandle> observed,
            int seconds,
            java.util.concurrent.atomic.AtomicBoolean interrupted) {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        if (process.isAlive()) process.descendants().limit(129).forEach(observed::add);
        for (var child : observed) if (child.isAlive()) child.destroy();
        // Let the parent reap its children before terminating it.
        if (!observed.isEmpty() && process.isAlive()) {
            try {
                process.waitFor(
                        Math.min(
                                TimeUnit.MILLISECONDS.toNanos(150),
                                Math.max(1, end - System.nanoTime())),
                        TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                interrupted.set(true);
            }
        }
        for (var child : observed) if (child.isAlive()) child.destroyForcibly();
        if (process.isAlive()) process.destroy();
        try {
            if (process.isAlive()
                    && !process.waitFor(
                            Math.min(
                                    TimeUnit.MILLISECONDS.toNanos(150),
                                    Math.max(1, end - System.nanoTime())),
                            TimeUnit.NANOSECONDS)) process.destroyForcibly();
            if (process.isAlive())
                process.waitFor(Math.max(1, end - System.nanoTime()), TimeUnit.NANOSECONDS);
            for (var child : observed)
                if (child.isAlive())
                    child.onExit().get(Math.max(1, end - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            interrupted.set(true);
        } catch (Exception ignored) {
        }
        return !process.isAlive() && observed.stream().noneMatch(ProcessHandle::isAlive);
    }

    private static boolean contains(byte[] value, byte[] secret) {
        if (secret.length == 0) return false;
        outer:
        for (int i = 0; i <= value.length - secret.length; i++) {
            for (int j = 0; j < secret.length; j++) if (value[i + j] != secret[j]) continue outer;
            return true;
        }
        return false;
    }

    private static boolean containsJsonSecret(byte[] value, byte[] secret) {
        if (secret.length == 0) return false;
        try {
            String encoded =
                    new com.fasterxml.jackson.databind.ObjectMapper()
                            .writeValueAsString(new String(secret, StandardCharsets.UTF_8));
            return contains(
                    value,
                    encoded.substring(1, encoded.length() - 1).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return true;
        }
    }

    private SecurityAuditProcesses() {}
}
