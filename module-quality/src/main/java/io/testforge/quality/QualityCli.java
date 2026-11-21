package io.testforge.quality;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.testforge.artifact.ArtifactSink;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;

/**
 * The quality gate as a command, for whoever runs the build — a CI step, a person, an agent — without
 * a Spring test around it:
 *
 * <pre>
 * testforge-quality check    [--config quality.yml] [--set key=value]… [--json]
 * testforge-quality baseline [--config quality.yml] [--set key=value]…
 * testforge-quality release  [--config quality.yml] [--set key=value]… [--json]
 * </pre>
 *
 * The configuration is the same {@code forge.quality.*} an {@code application.yml} holds; {@code --set}
 * keys may leave out the {@code forge.quality.} prefix. {@code check} prints the pull-request summary
 * (or {@code quality.json} with {@code --json}) and exits with:
 *
 * <ul>
 *   <li>{@value #PASS} — the gate passes;</li>
 *   <li>{@value #FAILED} — the gate fails: {@code gate.failures} in {@code quality.json} says why;</li>
 *   <li>{@value #CANNOT_JUDGE} — nothing could be measured, or an input could not be read;</li>
 *   <li>{@value #USAGE} — the command line is wrong.</li>
 * </ul>
 *
 * {@code baseline} promotes the run it judges to {@code baseline-file} — run it on purpose, in a
 * reviewed change. {@code release} answers whether the build can ship by {@code forge.quality.release}'s
 * absolute rules and exits {@value #PASS} READY, {@value #FAILED} NOT_READY, {@value #CANNOT_JUDGE}
 * UNKNOWN (a required dimension not measured). Nothing is registered in a run index: the command only reads what the build left.
 */
public final class QualityCli {

    static final int PASS = 0;
    static final int FAILED = 1;
    static final int CANNOT_JUDGE = 2;
    static final int USAGE = 64;
    private static final String PREFIX = "forge.quality.";

    private QualityCli() {
    }

    public static void main(String[] args) {
        // the libraries log through SLF4J and the command ships no provider: its own output is the report
        System.setProperty("slf4j.internal.verbosity", "ERROR");
        System.exit(run(args, System.out, System.err));
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        List<String> arguments = new ArrayList<>(List.of(args));
        String command = arguments.isEmpty() || arguments.getFirst().startsWith("--") ? "check"
                : arguments.removeFirst();
        if (!command.equals("check") && !command.equals("baseline") && !command.equals("release")) {
            return usage(err, "unknown command " + command);
        }
        Path config = null;
        boolean json = false;
        Map<String, Object> settings = new LinkedHashMap<>();
        for (int i = 0; i < arguments.size(); i++) {
            String argument = arguments.get(i);
            switch (argument) {
                case "--json" -> json = true;
                case "--config", "--set" -> {
                    if (i + 1 >= arguments.size()) {
                        return usage(err, argument + " needs a value");
                    }
                    String value = arguments.get(++i);
                    if (argument.equals("--config")) {
                        config = Path.of(value);
                    } else if (!value.contains("=")) {
                        return usage(err, "--set takes key=value, got " + value);
                    } else {
                        String key = value.substring(0, value.indexOf('=')).strip();
                        settings.put(key.startsWith(PREFIX) ? key : PREFIX + key,
                                value.substring(value.indexOf('=') + 1));
                    }
                }
                default -> {
                    return usage(err, "unknown option " + argument);
                }
            }
        }
        try {
            QualityRunner runner = new QualityRunner(properties(config, settings), new ObjectMapper(),
                    ArtifactSink.NO_OP);
            if (command.equals("release")) {
                ReleaseReport release = runner.release();
                out.print(Files.readString(Path.of(json ? release.reportJson() : release.reportMarkdown())));
                err.println("release: " + release.verdict() + " — " + release.reportJson());
                return switch (release.verdict()) {
                    case READY -> PASS;
                    case NOT_READY -> FAILED;
                    case UNKNOWN -> CANNOT_JUDGE;
                };
            }
            QualityReport report = command.equals("baseline") ? runner.writeBaseline() : runner.run();
            out.print(json ? Files.readString(Path.of(report.reportJson()))
                    : Files.readString(Path.of(report.reportJson()).resolveSibling(QualityReportWriter.SUMMARY)));
            if (report.overall() == QualityStatus.NOT_MEASURED) {
                err.println("quality: nothing could be measured — see " + report.reportMarkdown());
                return CANNOT_JUDGE;
            }
            if (command.equals("baseline")) {
                err.println("quality: baseline written from run " + report.runId());
                return PASS;
            }
            err.println("quality gate: " + report.gate().verdict() + " — " + report.reportJson());
            return report.gate().verdict() == QualityStatus.PASS ? PASS : FAILED;
        } catch (QualityException | org.springframework.boot.context.properties.bind.BindException
                 | IllegalArgumentException | IllegalStateException | IOException e) {
            err.println("quality: cannot judge — " + e.getMessage()
                    + (e.getCause() == null ? "" : " (" + e.getCause().getMessage() + ")"));
            return CANNOT_JUDGE;
        }
    }

    /** {@code forge.quality.*} from {@code --set}, then the YAML file, then system properties and the environment. */
    static QualityProperties properties(Path config, Map<String, Object> settings) throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("command line", settings));
        if (config != null) {
            if (!Files.isRegularFile(config)) {
                throw new QualityException("No configuration at " + config);
            }
            for (PropertySource<?> source : new YamlPropertySourceLoader().load(config.toString(),
                    new FileSystemResource(config))) {
                environment.getPropertySources().addAfter("command line", source);
            }
        }
        return Binder.get(environment).bindOrCreate("forge.quality", QualityProperties.class);
    }

    private static int usage(PrintStream err, String problem) {
        err.println("quality: " + problem);
        err.println("usage: testforge-quality check|baseline|release [--config quality.yml] [--set key=value]... "
                + "[--json]");
        return USAGE;
    }
}
