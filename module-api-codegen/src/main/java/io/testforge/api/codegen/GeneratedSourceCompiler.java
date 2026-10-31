package io.testforge.api.codegen;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/**
 * Compiles one spec's generated sources, so code that would not compile is
 * reported by the run that wrote it — not by whoever first puts it on a
 * classpath. Classes go next to the sources, inside the spec's own directory.
 */
final class GeneratedSourceCompiler {

    static final int MAX_ERRORS = 20;

    private final String classpath;

    GeneratedSourceCompiler(String classpath) {
        this.classpath = classpath;
    }

    GeneratedSourceCompiler() {
        this(System.getProperty("java.class.path", ""));
    }

    Result compile(Path sourceRoot, List<Path> files, Path classesDir) {
        if (files.isEmpty()) {
            return new Result(true, List.of(), null);
        }
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            return new Result(null, List.of(), "no system Java compiler (the JVM is a JRE, not a JDK)");
        }
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager fileManager =
                compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            Files.createDirectories(classesDir);
            List<String> options = List.of(
                    "-classpath", classpath,
                    "-d", classesDir.toString(),
                    "-encoding", "UTF-8",
                    "-proc:none",
                    "-Xlint:none");
            Iterable<? extends JavaFileObject> units =
                    fileManager.getJavaFileObjectsFromFiles(files.stream().map(Path::toFile).toList());
            boolean ok = Boolean.TRUE.equals(compiler.getTask(null, fileManager, diagnostics, options, null, units)
                    .call());
            List<String> errors = errors(sourceRoot, diagnostics);
            if (!ok && errors.isEmpty()) {
                errors = List.of("the compiler failed without reporting an error");
            }
            return new Result(ok, errors, null);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to compile generated sources in " + sourceRoot, e);
        }
    }

    private static List<String> errors(Path sourceRoot, DiagnosticCollector<JavaFileObject> diagnostics) {
        List<String> errors = new ArrayList<>();
        int total = 0;
        for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
            if (diagnostic.getKind() != Diagnostic.Kind.ERROR) {
                continue;
            }
            total++;
            if (errors.size() < MAX_ERRORS) {
                errors.add(location(sourceRoot, diagnostic) + ": " + diagnostic.getMessage(Locale.ROOT)
                        .lines().findFirst().orElse(""));
            }
        }
        if (total > errors.size()) {
            errors.add("... and " + (total - errors.size()) + " more");
        }
        return List.copyOf(errors);
    }

    private static String location(Path sourceRoot, Diagnostic<? extends JavaFileObject> diagnostic) {
        if (diagnostic.getSource() == null) {
            return "(no file)";
        }
        Path file = Path.of(diagnostic.getSource().toUri());
        String name = file.startsWith(sourceRoot)
                ? sourceRoot.relativize(file).toString().replace(File.separatorChar, '/')
                : file.getFileName().toString();
        return name + ":" + diagnostic.getLineNumber();
    }

    /**
     * @param compiled {@code true} or {@code false}, or {@code null} when the
     *        check could not run ({@code skipped} says why)
     */
    record Result(Boolean compiled, List<String> errors, String skipped) {
    }
}
