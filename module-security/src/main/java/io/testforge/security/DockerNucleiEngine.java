package io.testforge.security;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

public final class DockerNucleiEngine implements NucleiEngine {
    public static final String IMAGE =
            "projectdiscovery/nuclei@sha256:582d5546902e67052097cb2d07296c642d50a1afc5e44623cb038845df9a32eb";
    static final String TEMPLATE = "http-missing-security-headers";
    private static final String TEMPLATE_SHA256 =
            "7f45803f73ac6810b9b302df6be2c472a82d9164a7807328614efa83e6eac275";
    private static final int MAX_REPORT_BYTES = 1024 * 1024;

    @Override
    public Result execute(Path dir, List<String> targets, SecurityNucleiProperties p) {
        String name = "testforge-nuclei-" + UUID.randomUUID();
        Result result;
        BoundedCommand.Outcome cleaned;
        try {
            result = run(dir, targets, p, name);
        } catch (Exception e) {
            result = new Result(Phase.EXECUTION, null);
        } finally {
            // BoundedCommand preserves interruption. Allow the cleanup command to finish first.
            boolean interrupted = Thread.interrupted();
            try {
                cleaned =
                        BoundedCommand.run(
                                List.of("docker", "rm", "-f", name), Duration.ofSeconds(10));
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
        }
        return cleaned.success() ? result : new Result(Phase.CLEANUP, null);
    }

    private Result run(Path dir, List<String> targets, SecurityNucleiProperties p, String name)
            throws Exception {
        Path input = Files.createDirectory(dir.resolve("input"));
        try (var resource = getClass().getResourceAsStream("nuclei/" + TEMPLATE + ".yaml")) {
            byte[] template = resource.readNBytes(64 * 1024);
            String hash =
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(template));
            if (!TEMPLATE_SHA256.equals(hash) || resource.read() != -1)
                return new Result(Phase.EXECUTION, null);
            Files.write(input.resolve(TEMPLATE + ".yaml"), template);
        }
        Files.write(input.resolve("targets.txt"), targets, StandardCharsets.UTF_8);
        var args =
                new ArrayList<>(
                        List.of(
                                "docker",
                                "run",
                                "--name",
                                name,
                                "--pull=never",
                                "--read-only",
                                "--log-driver=none",
                                "--cap-drop=ALL",
                                "--security-opt=no-new-privileges",
                                "--memory=512m",
                                "--cpus=1",
                                "--pids-limit=128",
                                "--tmpfs",
                                "/tmp:rw,nosuid,nodev,noexec,size=64m",
                                "--user",
                                Files.getAttribute(dir, "unix:uid")
                                        + ":"
                                        + Files.getAttribute(dir, "unix:gid"),
                                "--env",
                                "HOME=/tmp",
                                "--env",
                                "XDG_CONFIG_HOME=/tmp/config",
                                "--env",
                                "DISABLE_NUCLEI_TEMPLATES_GITHUB_DOWNLOAD=true",
                                "--env",
                                "DISABLE_NUCLEI_TEMPLATES_GITLAB_DOWNLOAD=true",
                                "--env",
                                "DISABLE_NUCLEI_TEMPLATES_AWS_DOWNLOAD=true",
                                "--env",
                                "DISABLE_NUCLEI_TEMPLATES_AZURE_DOWNLOAD=true",
                                "--mount",
                                "type=bind,source="
                                        + input.toAbsolutePath()
                                        + ",target=/scan,readonly"));
        if (DockerZapEngine.hostNetwork()) args.addAll(List.of("--network", "host"));
        args.addAll(
                List.of(
                        "--entrypoint",
                        "/bin/sh",
                        IMAGE,
                        "-ec",
                        "ulimit -f 1024; nuclei \"$@\" > /tmp/matches; cat /tmp/matches",
                        "nuclei",
                        "-l",
                        "/scan/targets.txt",
                        "-t",
                        "/scan/" + TEMPLATE + ".yaml",
                        "-id",
                        TEMPLATE,
                        "-tags",
                        "headers",
                        "-pt",
                        "http",
                        "-dut",
                        "-duc",
                        "-ni",
                        "-dr",
                        "-nh",
                        "-no-stdin",
                        "-jsonl",
                        "-omit-raw",
                        "-omit-template",
                        "-silent",
                        "-no-color",
                        "-c",
                        "1",
                        "-bs",
                        "1",
                        "-retries",
                        "0",
                        "-timeout",
                        "10",
                        "-rl",
                        Integer.toString(p.maxRequestsPerSecond())));
        Path report = Files.createFile(dir.resolve("matches.jsonl"));
        var outcome = BoundedCommand.run(args, p.timeBudget(), null, report);
        if (!outcome.success())
            return new Result(outcome.timedOut() ? Phase.TIMEOUT : Phase.EXECUTION, null);
        if (Files.size(report) >= MAX_REPORT_BYTES) return new Result(Phase.REPORT, null);
        var mapper = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        var matches = mapper.createArrayNode();
        try (var lines = Files.newBufferedReader(report)) {
            String line;
            while ((line = lines.readLine()) != null) {
                if (line.isBlank()) continue;
                matches.add(mapper.readTree(line));
            }
        } catch (Exception e) {
            return new Result(Phase.REPORT, null);
        }
        return new Result(Phase.COMPLETE, matches);
    }
}
