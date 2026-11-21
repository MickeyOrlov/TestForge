package io.testforge.security.audit;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.testforge.artifact.ArtifactSink;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;

/** File-output CLI. No adapter commands or secrets can be supplied through CLI/profile fields. */
public final class SecurityAuditCli {
    private final Collection<SecurityAuditAdapter> adapters;
    private final SecurityAuditExecutionContext.Secrets secrets;

    public SecurityAuditCli() {
        this(
                List.of(),
                reference -> {
                    throw new IllegalStateException("credential resolver is not configured");
                });
    }

    public SecurityAuditCli(
            Collection<SecurityAuditAdapter> adapters,
            SecurityAuditExecutionContext.Secrets secrets) {
        this.adapters = List.copyOf(adapters);
        this.secrets = secrets;
    }

    /**
     * 0=healthy run or successful plan; 2=complete unhealthy run; 3=incomplete/configuration
     * failure.
     */
    public int execute(String... args) {
        try {
            if (args.length < 5 || !List.of("plan", "run").contains(args[0])) return 3;
            var options = new HashMap<String, String>();
            boolean enable = false;
            for (int i = 1; i < args.length; i++) {
                if (args[i].equals("--enable-run")) {
                    if (enable) return 3;
                    enable = true;
                    continue;
                }
                if (!List.of("--profile", "--output").contains(args[i])
                        || i + 1 == args.length
                        || options.put(args[i], args[++i]) != null) return 3;
            }
            if (!options.keySet().equals(java.util.Set.of("--profile", "--output"))
                    || args[0].equals("run") && !enable) return 3;
            Path input = Path.of(options.get("--profile"));
            byte[] bytes = SecurityAuditFiles.read(input, 1048576);
            String json =
                    StandardCharsets.UTF_8
                            .newDecoder()
                            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                            .decode(java.nio.ByteBuffer.wrap(bytes))
                            .toString();
            var profile = SecurityAuditProfiles.parse(json);
            var properties = new SecurityAuditProperties(true, null, null, null, null, null);
            var plan = new SecurityAuditPlanner(properties).plan(profile);
            if (args[0].equals("plan")) {
                byte[] output =
                        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsBytes(plan);
                if (output.length > 2097152) return 3;
                Path directory = SecurityAuditFiles.directory(options.get("--output"), "plan-");
                Files.write(
                        directory.resolve("security-audit-plan.json"),
                        output,
                        java.nio.file.StandardOpenOption.CREATE_NEW);
                return 0;
            }
            var results = new SecurityAuditResultsProperties(options.get("--output"), null, null);
            var execution =
                    new SecurityAuditExecutionProperties(
                            true,
                            Path.of(options.get("--output")).resolve("private-work").toString(),
                            null,
                            null,
                            null);
            var receipt =
                    new SecurityAuditExecutor(
                                    properties,
                                    execution,
                                    results,
                                    adapters,
                                    secrets,
                                    ArtifactSink.NO_OP)
                            .run(profile);
            return !receipt.assessment().complete() ? 3 : receipt.assessment().healthy() ? 0 : 2;
        } catch (Exception e) {
            return 3;
        }
    }

    public static void main(String[] args) {
        System.exit(new SecurityAuditCli().execute(args));
    }
}
