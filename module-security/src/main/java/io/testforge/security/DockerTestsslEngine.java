package io.testforge.security;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * testssl.sh in its pinned image: read-only root, no capabilities, bounded CPU/PIDs/RAM, the
 * caller's user, one private output directory, no DNS beyond A/AAAA/MX and no CRL/OCSP calls to
 * third parties ({@code --phone-out} stays off). The container is removed even on error.
 */
public class DockerTestsslEngine implements TestsslEngine {
    static final int MAX_REPORT_BYTES = 8 * 1024 * 1024;

    /** The fixed policy: protocols, cipher categories, forward secrecy, certificate, vulnerabilities. */
    static final List<String> POLICY =
            List.of("--protocols", "--std", "--fs", "--server-defaults", "--vulnerable");

    @Override
    public Result execute(Path dir, String hostPort, SecurityTransportProperties.Testssl p) {
        String name = "testforge-testssl-" + UUID.randomUUID();
        Result result;
        BoundedCommand.Outcome cleaned;
        try {
            result = run(dir, hostPort, p, name);
        } catch (Exception e) {
            result = new Result(null, Phase.EXECUTION, -1);
        } finally {
            cleaned =
                    BoundedCommand.run(
                            List.of("docker", "rm", "-f", name), Duration.ofSeconds(10));
        }
        return cleaned.success() ? result : new Result(null, Phase.CLEANUP, cleaned.exitCode());
    }

    private Result run(
            Path dir, String hostPort, SecurityTransportProperties.Testssl p, String name)
            throws Exception {
        var args =
                new ArrayList<>(
                        List.of(
                                "docker",
                                "run",
                                "--name",
                                name,
                                "--pull=never",
                                "--read-only",
                                "--tmpfs",
                                "/tmp:rw,nosuid,nodev,size=64m",
                                "--cap-drop=ALL",
                                "--security-opt=no-new-privileges",
                                "--memory=512m",
                                "--cpus=1",
                                "--pids-limit=256"));
        try {
            args.addAll(
                    List.of(
                            "--user",
                            Files.getAttribute(dir, "unix:uid")
                                    + ":"
                                    + Files.getAttribute(dir, "unix:gid")));
        } catch (UnsupportedOperationException | IllegalArgumentException ignored) {
            // no POSIX owner (Windows): the image's own user writes the bind mount
        }
        boolean host = DockerZapEngine.hostNetwork();
        if (host) args.addAll(List.of("--network", "host"));
        for (String alias : p.hostAliases())
            args.addAll(
                    List.of("--add-host", alias + ":" + (host ? "127.0.0.1" : "host-gateway")));
        args.addAll(List.of("-v", dir.toAbsolutePath() + ":/out", p.image()));
        args.addAll(
                List.of(
                        "--quiet",
                        "--color",
                        "0",
                        "--warnings",
                        "off",
                        "--nodns",
                        "min",
                        "--connect-timeout",
                        "10",
                        "--openssl-timeout",
                        "10"));
        args.addAll(POLICY);
        args.addAll(List.of("--jsonfile", "/out/testssl.json", hostPort));
        BoundedCommand.Outcome outcome = BoundedCommand.run(args, p.timeBudget());
        if (outcome.timedOut()) return new Result(null, Phase.TIMEOUT, -1);
        Path report = dir.resolve("testssl.json");
        if (!Files.isRegularFile(report) || Files.size(report) > MAX_REPORT_BYTES)
            return new Result(null, Phase.REPORT, outcome.exitCode());
        return new Result(
                new ObjectMapper().readTree(report.toFile()),
                outcome.exitCode() == 0 ? Phase.READY : Phase.EXECUTION,
                outcome.exitCode());
    }
}
