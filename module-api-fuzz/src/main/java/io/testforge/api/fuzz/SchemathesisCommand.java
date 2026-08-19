package io.testforge.api.fuzz;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Builds the command line argument list for executing the Schemathesis CLI ({@code st}).
 *
 * <p><strong>Safety and secret protection:</strong>
 * ABSOLUTELY FORBIDDEN: Do not build {@code -H}, {@code --header}, {@code --auth}, or {@code -a} arguments here.
 * Schemathesis's NDJSON report persists the full command line in its {@code Initialize} event,
 * so any authentication token or secret passed in {@code argv} ends up persisted inside a TestForge artifact.
 * Authentication belongs in the user's own {@code schemathesis.toml} using environment substitution
 * like {@code bearer = "$STAGING_TOKEN"}.
 *
 * <p>The produced command structure follows a stable order:
 * <pre>
 * &lt;command&gt; --config-file &lt;generated toml&gt; [--config-file &lt;user toml&gt;] run &lt;specPath&gt; -u &lt;baseUrl&gt;
 *   --include-method &lt;M&gt; (repeated per permitted method)
 *   --phases &lt;comma-joined phases&gt;
 *   -m &lt;generationMode&gt;
 *   -n &lt;maxExamples&gt;
 *   [--seed &lt;seed&gt;]
 *   --report junit,ndjson --report-dir &lt;outputDir&gt;
 *   --output-sanitize true
 *   --no-color
 *   [--max-failures &lt;maxFailures&gt;]
 * </pre>
 *
 * <p>Note: {@code --config-file} is a global option in Schemathesis CLI and must appear before
 * the {@code run} subcommand.
 */
public class SchemathesisCommand {

    private final ApiFuzzProperties properties;
    private final FuzzSafetyPolicy policy;
    private final String specPath;
    private final Path generatedConfigFile;

    /**
     * Constructs a command builder instance.
     *
     * @param properties          the configuration properties
     * @param policy              the effective safety policy
     * @param specPath            path or location of the OpenAPI specification file
     * @param generatedConfigFile path to TestForge's generated {@code schemathesis.toml}
     */
    public SchemathesisCommand(
            ApiFuzzProperties properties,
            FuzzSafetyPolicy policy,
            Path specPath,
            Path generatedConfigFile) {
        this.properties = Objects.requireNonNull(properties, "properties cannot be null");
        this.policy = Objects.requireNonNull(policy, "policy cannot be null");
        Objects.requireNonNull(specPath, "specPath cannot be null");
        this.specPath = specPath.toString();
        this.generatedConfigFile = Objects.requireNonNull(generatedConfigFile, "generatedConfigFile cannot be null");
    }

    /**
     * Constructs a command builder instance with a string specPath.
     *
     * @param properties          the configuration properties
     * @param policy              the effective safety policy
     * @param specPath            path or location of the OpenAPI specification file
     * @param generatedConfigFile path to TestForge's generated {@code schemathesis.toml}
     */
    public SchemathesisCommand(
            ApiFuzzProperties properties,
            FuzzSafetyPolicy policy,
            String specPath,
            Path generatedConfigFile) {
        this.properties = Objects.requireNonNull(properties, "properties cannot be null");
        this.policy = Objects.requireNonNull(policy, "policy cannot be null");
        if (specPath == null || specPath.isBlank()) {
            throw new ApiFuzzException("specPath must be specified");
        }
        this.specPath = specPath;
        this.generatedConfigFile = Objects.requireNonNull(generatedConfigFile, "generatedConfigFile cannot be null");
    }

    /** The per-spec directory this run's artifacts belong in. */
    private Path reportDir() {
        Path parent = generatedConfigFile.toAbsolutePath().getParent();
        return parent != null ? parent : Path.of(properties.outputDir());
    }

    /**
     * Builds the argument list for executing Schemathesis.
     *
     * @return unmodifiable list of CLI command arguments
     */
    public List<String> build() {
        if (properties.baseUrl() == null || properties.baseUrl().isBlank()) {
            throw new ApiFuzzException("Base URL must be configured (forge.api-fuzz.base-url) to build Schemathesis command");
        }

        List<String> args = new ArrayList<>();

        // Executable command name (defaults to "st")
        String command = properties.command() != null && !properties.command().isBlank()
                ? properties.command()
                : "st";
        args.add(command);

        // Global options MUST appear before the "run" subcommand.
        //
        // Absolute, because the child process does not run here: ApiFuzzRunner
        // starts the CLI with its working directory set to the per-spec output
        // directory. A relative path — which is what the documented default
        // `output-dir: build/api-fuzz` produces — is then resolved against that
        // directory and looks for `<out>/<spec>/<out>/<spec>/schemathesis.toml`,
        // so Schemathesis aborts before testing anything. `--report-dir` below
        // has always been absolute for the same reason.
        args.add("--config-file");
        args.add(generatedConfigFile.toAbsolutePath().normalize().toString());

        // Overlay user config file if supplied (passed as second --config-file global option).
        if (properties.configFile() != null && !properties.configFile().isBlank()) {
            args.add("--config-file");
            args.add(properties.configFile());
        }

        // Subcommand and target inputs
        args.add("run");
        args.add(specPath);
        args.add("-u");
        args.add(properties.baseUrl());

        // Permitted methods (--include-method M for each permitted method)
        for (String method : policy.permittedMethods()) {
            args.add("--include-method");
            args.add(method);
        }

        // Fuzzing phases
        args.add("--phases");
        args.add(String.join(",", properties.phases()));

        // Generation mode
        args.add("-m");
        args.add(properties.generationMode());

        // Max examples per operation
        args.add("-n");
        args.add(String.valueOf(properties.maxExamples()));

        // Seed if configured
        if (properties.seed() != null) {
            args.add("--seed");
            args.add(String.valueOf(properties.seed()));
        }

        // Reports go beside this run's generated config, which is already the
        // per-spec directory. Pointing at the shared forge.api-fuzz.output-dir
        // instead would resolve relative to the process working directory
        // (producing an odd nested path), and with an ABSOLUTE output-dir every
        // spec in a multi-spec run would write its reports over the previous
        // spec's.
        args.add("--report");
        args.add("junit,ndjson");
        args.add("--report-dir");
        args.add(reportDir().toString());

        // Sanitize output
        args.add("--output-sanitize");
        args.add("true");

        // Color output disabled for deterministic CI log parsing
        args.add("--no-color");

        // Max failures if configured
        if (properties.maxFailures() != null) {
            args.add("--max-failures");
            args.add(String.valueOf(properties.maxFailures()));
        }

        /*
         * FORBIDDEN CHECK: Ensure no authentication or header arguments (-H, --header, --auth, -a) were added.
         * Schemathesis persists full CLI args in NDJSON reports, exposing CLI tokens.
         */
        for (String arg : args) {
            if ("-H".equals(arg) || "--header".equals(arg) || "--auth".equals(arg) || "-a".equals(arg)) {
                throw new ApiFuzzException("Forbidden header/auth argument in Schemathesis CLI args: " + arg);
            }
        }

        return Collections.unmodifiableList(args);
    }

    /**
     * Static helper to build the Schemathesis argument list.
     *
     * @param properties          the configuration properties
     * @param policy              the effective safety policy
     * @param specPath            path to the OpenAPI specification file
     * @param generatedConfigFile path to TestForge's generated {@code schemathesis.toml}
     * @return unmodifiable list of CLI command arguments
     */
    public static List<String> build(
            ApiFuzzProperties properties,
            FuzzSafetyPolicy policy,
            Path specPath,
            Path generatedConfigFile) {
        return new SchemathesisCommand(properties, policy, specPath, generatedConfigFile).build();
    }
}
