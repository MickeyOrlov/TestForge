package io.testforge.security;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Pinned image, bounded execution, ephemeral private reports and unconditional container cleanup.
 */
public class DockerZapEngine implements ZapEngine {
    static boolean hostNetwork() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("linux");
    }

    static String gatewayHost() {
        return hostNetwork() ? "127.0.0.1" : "host.docker.internal";
    }

    @Override
    public Result execute(Path dir, SecurityActiveProperties p) {
        String name = "testforge-security-" + UUID.randomUUID();
        Result result;
        BoundedCommand.Outcome cleaned;
        try {
            result = executeContainer(dir, p, name);
        } catch (Exception e) {
            result = new Result(false, null);
        } finally {
            cleaned = BoundedCommand.run(List.of("docker", "rm", "-f", name), Duration.ofSeconds(10));
        }
        return cleaned.success()
                ? result
                : new Result(false, null, Phase.CLEANUP, cleaned.exitCode());
    }

    private Result executeContainer(Path dir, SecurityActiveProperties p, String name) {
        try {
            var start =
                    new ArrayList<>(
                            List.of(
                                    "docker",
                                    "run",
                                    "-d",
                                    "--name",
                                    name,
                                    "--pull=never",
                                    "--memory=2g",
                                    "--cpus=2",
                                    "--pids-limit=256",
                                    "--cap-drop=ALL",
                                    "--security-opt=no-new-privileges",
                                    "--read-only",
                                    "--tmpfs",
                                    "/home/zap/.ZAP:rw,nosuid,nodev,size=768m,uid=1000,gid=1000",
                                    "--tmpfs",
                                    "/tmp:rw,nosuid,nodev,size=128m",
                                    "--tmpfs",
                                    "/home/zap/.java:rw,nosuid,nodev,size=16m,uid=1000,gid=1000",
                                    "--tmpfs",
                                    "/zap/wrk:rw,nosuid,nodev,size=128m,uid=1000,gid=1000"));
            if (hostNetwork()) start.addAll(List.of("--network", "host"));
            start.addAll(List.of("--entrypoint", "tail", p.image(), "-f", "/dev/null"));
            BoundedCommand.Outcome startup = BoundedCommand.run(start, Duration.ofSeconds(30));
            if (!startup.success())
                return new Result(
                        false,
                        null,
                        startup.timedOut() ? Phase.TIMEOUT : Phase.STARTUP,
                        startup.exitCode());
            for (String file : List.of("plan.json", "openapi.json")) {
                BoundedCommand.Outcome copied =
                        BoundedCommand.run(
                                List.of(
                                        "docker",
                                        "exec",
                                        "-i",
                                        name,
                                        "python3",
                                        "-c",
                                        "import os,sys,shutil;"
                                            + " f=os.fdopen(os.open(sys.argv[1],os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600),'wb');"
                                            + " shutil.copyfileobj(sys.stdin.buffer,f); f.close()",
                                        "/zap/wrk/" + file),
                                Duration.ofSeconds(10),
                                dir.resolve(file),
                                null);
                if (!copied.success())
                    return new Result(false, null, Phase.INPUT, copied.exitCode());
            }
            BoundedCommand.Outcome complete =
                    BoundedCommand.run(
                            List.of(
                                    "docker",
                                    "exec",
                                    name,
                                    "/zap/zap.sh",
                                    "-Xmx512m",
                                    "-cmd",
                                    "-silent",
                                    "-autorun",
                                    "/zap/wrk/plan.json",
                                    "-config",
                                    "autoupdate.checkOnStart=false",
                                    "-config",
                                    "autoupdate.downloadNewRelease=false",
                                    "-config",
                                    "autoupdate.checkAddonUpdates=false"),
                            p.timeBudget());
            if (!complete.success())
                return new Result(
                        false,
                        null,
                        complete.timedOut() ? Phase.TIMEOUT : Phase.EXECUTION,
                        complete.exitCode());
            Path report = dir.resolve("raw-security.json");
            Files.createFile(report);
            try {
                Files.setPosixFilePermissions(
                        report,
                        java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) {
            }
            BoundedCommand.Outcome copied =
                    BoundedCommand.run(
                            List.of(
                                    "docker",
                                    "exec",
                                    name,
                                    "python3",
                                    "-c",
                                    "import os,sys; p=sys.argv[1]; sys.exit(3) if"
                                        + " os.stat(p).st_size>16*1024*1024 else None;"
                                        + " f=open(p,'rb'); data=f.read(16*1024*1024+1);"
                                        + " sys.exit(3) if len(data)>16*1024*1024 else None;"
                                        + " sys.stdout.buffer.write(data)",
                                    "/zap/wrk/raw-security.json"),
                            Duration.ofSeconds(10),
                            null,
                            report);
            if (!copied.success()) return new Result(false, null, Phase.REPORT, copied.exitCode());
            if (Files.size(report) > 16L * 1024 * 1024)
                return new Result(false, null, Phase.REPORT, -1);
            return new Result(true, new ObjectMapper().readTree(report.toFile()));
        } catch (Exception e) {
            return new Result(false, null);
        }
    }
}
