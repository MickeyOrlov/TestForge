package io.testforge.api.codegen;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
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

    /** What generated sources import; each must be found on a file classpath for the check to mean anything. */
    static final List<String> REQUIRED_CLASSES = List.of(
            "io.testforge.http.ApiClient",
            "io.restassured.response.Response",
            "io.restassured.specification.RequestSpecification",
            "com.fasterxml.jackson.annotation.JsonProperty");

    private final String classpath;

    /** Compiles against exactly {@code classpath}. */
    GeneratedSourceCompiler(String classpath) {
        this.classpath = classpath;
    }

    /** Compiles against the JVM's classpath plus wherever the classes generated code imports were loaded from. */
    GeneratedSourceCompiler() {
        this(null);
    }

    Result compile(Path sourceRoot, List<Path> files, Path classesDir) {
        if (files.isEmpty()) {
            return new Result(true, List.of(), null);
        }
        String classpath = this.classpath;
        if (classpath == null) {
            Classpath resolved = resolve(System.getProperty("java.class.path", ""), GeneratedSourceCompiler::locate);
            if (resolved.skipped() != null) {
                return new Result(null, List.of(), resolved.skipped());
            }
            classpath = resolved.value();
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

    /**
     * {@code java.class.path} alone is not trusted: in a Spring Boot fat jar it
     * names one archive whose nested jars javac cannot read, and every spec
     * would "fail to compile". The classes generated code imports are located
     * where they were actually loaded from; one that is not a plain file or
     * directory skips the check with the reason instead of failing it.
     */
    static Classpath resolve(String javaClassPath, Function<String, URL> locate) {
        Set<String> entries = new LinkedHashSet<>();
        for (String entry : javaClassPath.split(File.pathSeparator)) {
            if (!entry.isBlank()) {
                entries.add(entry);
            }
        }
        for (String required : REQUIRED_CLASSES) {
            URL location = locate.apply(required);
            if (location == null) {
                return new Classpath(null, required + " is not on the classpath");
            }
            if (!"file".equals(location.getProtocol())) {
                return new Classpath(null, required + " was loaded from " + location
                        + ", which the compiler cannot read (a Spring Boot fat jar, for example); generate "
                        + "from a test task, or set forge.api-codegen.compile=false");
            }
            try {
                entries.add(Path.of(location.toURI()).toString());
            } catch (URISyntaxException | IllegalArgumentException e) {
                return new Classpath(null, required + " has an unreadable location " + location);
            }
        }
        return new Classpath(String.join(File.pathSeparator, entries), null);
    }

    private static URL locate(String className) {
        try {
            Class<?> type = Class.forName(className, false, GeneratedSourceCompiler.class.getClassLoader());
            CodeSource source = type.getProtectionDomain().getCodeSource();
            return source == null ? null : source.getLocation();
        } catch (ClassNotFoundException | LinkageError e) {
            return null;
        }
    }

    record Classpath(String value, String skipped) {
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
