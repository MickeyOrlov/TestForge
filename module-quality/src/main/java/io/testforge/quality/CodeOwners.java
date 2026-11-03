package io.testforge.quality;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * A repository's {@code CODEOWNERS}, so issues need no second owner mapping: an issue key that
 * points at a file — a test or mutated class ({@code io.acme.orders.OrdersTest.pays()}), a changed
 * source file ({@code src/main/java/…/Prices.java}), a scanner finding's file
 * ({@code gitleaks jwt artifacts/session.txt}) — goes to the owners of that file. As on GitHub
 * the last matching rule wins, and a rule without owners leaves the file unowned.
 *
 * <p>A class name becomes a file by looking it up among the repository's sources once; an operation
 * or a table points at no file and stays with {@code components}.
 */
final class CodeOwners {

    private static final Set<String> SOURCES = Set.of(".java", ".kt", ".groovy", ".scala");
    private static final Set<String> SKIPPED = Set.of(".git", "build", "target", "node_modules", ".gradle");

    private record Rule(Pattern pattern, String owners) {
    }

    private final List<Rule> rules;
    private final Path root;
    private Map<String, String> sourcesByClass;

    private CodeOwners(List<Rule> rules, Path root) {
        this.rules = rules;
        this.root = root;
    }

    /** {@code .github/CODEOWNERS}, {@code docs/CODEOWNERS} or one at the root; the repository is its root. */
    static CodeOwners read(Path file) {
        if (!Files.isRegularFile(file)) {
            throw new QualityException("No CODEOWNERS at " + file + " (forge.quality.codeowners)");
        }
        Path parent = file.toAbsolutePath().getParent();
        String folder = parent.getFileName() == null ? "" : parent.getFileName().toString();
        Path root = folder.equals(".github") || folder.equals("docs") ? parent.getParent() : parent;
        List<Rule> rules = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(file)) {
                String rule = line.strip();
                if (rule.isEmpty() || rule.startsWith("#")) {
                    continue;
                }
                String[] parts = rule.split("\\s+", 2);
                String owners = parts.length < 2 ? "" : parts[1].replaceAll("\\s+#.*$", "").strip();
                rules.add(new Rule(pattern(parts[0]), owners.isEmpty() ? null : owners.replaceAll("\\s+", " ")));
            }
        } catch (IOException e) {
            throw new QualityException("Could not read " + file, e);
        }
        return new CodeOwners(rules, root);
    }

    /** The owners of the file {@code issue} points at; empty when it points at none or nobody owns it. */
    Optional<String> ownersOf(String issue) {
        return file(issue).flatMap(path -> {
            String owners = null;
            for (Rule rule : rules) {
                if (rule.pattern().matcher(path).matches()) {
                    owners = rule.owners();
                }
            }
            return Optional.ofNullable(owners);
        });
    }

    /**
     * A repository-relative path: the key's first word when it is a source path, else the source of its
     * class; failing both, a later word that is a file path ({@code gitleaks jwt artifacts/session.txt},
     * {@code Trivy CVE-1 pom.xml}) — never a URL path ({@code GET /owners/{id}}).
     */
    private Optional<String> file(String issue) {
        Optional<String> first = firstWordFile(issue.split(" ", 2)[0]);
        if (first.isPresent()) {
            return first;
        }
        String[] words = issue.split(" ");
        for (int i = 1; i < words.length; i++) {
            if (words[i].matches("[\\w.-]+(/[\\w.-]+)*\\.[A-Za-z0-9]+")) {
                return Optional.of(words[i]);
            }
        }
        return Optional.empty();
    }

    private Optional<String> firstWordFile(String key) {
        if (key.contains("/") && SOURCES.stream().anyMatch(key::endsWith)) {
            return Optional.of(key.startsWith("/") ? key.substring(1) : key);
        }
        String member = key.split("\\(", 2)[0];
        int method = member.lastIndexOf('.');
        if (method < 0) {
            return Optional.empty();
        }
        String type = member.substring(0, method).split("\\$", 2)[0];
        if (!type.matches("[\\w.]+\\.[A-Z]\\w*")) {
            return Optional.empty();
        }
        return Optional.ofNullable(sources().get(type));
    }

    private Map<String, String> sources() {
        if (sourcesByClass == null) {
            Map<String, String> found = new HashMap<>();
            try (Stream<Path> files = Files.walk(root)) {
                files.filter(Files::isRegularFile)
                        .filter(f -> SOURCES.stream().anyMatch(f.getFileName().toString()::endsWith))
                        .map(root::relativize)
                        .filter(f -> java.util.stream.StreamSupport.stream(f.spliterator(), false)
                                .noneMatch(part -> SKIPPED.contains(part.toString())))
                        .forEach(f -> {
                            String path = f.toString().replace('\\', '/');
                            String type = className(path);
                            if (type != null) {
                                found.putIfAbsent(type, path);
                            }
                        });
            } catch (IOException e) {
                throw new QualityException("Could not list the sources under " + root, e);
            }
            sourcesByClass = found;
        }
        return sourcesByClass;
    }

    /** {@code src/test/java/io/acme/T.java} as {@code io.acme.T}, from the source root on. */
    private static String className(String path) {
        int dot = path.lastIndexOf('.');
        String bare = path.substring(0, dot);
        for (String sourceRoot : List.of("/java/", "/kotlin/", "/groovy/", "/scala/")) {
            int at = bare.indexOf(sourceRoot);
            if (at >= 0) {
                return bare.substring(at + sourceRoot.length()).replace('/', '.');
            }
        }
        return null;
    }

    /**
     * A CODEOWNERS pattern as GitHub reads it: a leading or inner {@code /} anchors it at the root,
     * otherwise it matches at any depth; a trailing {@code /} is a directory; {@code *} stays within
     * one directory, {@code **} crosses them. A literal last segment also owns what is below it
     * ({@code /build/logs}); a wildcard one does not ({@code docs/*} leaves {@code docs/a/b.md}).
     */
    static Pattern pattern(String glob) {
        boolean directory = glob.endsWith("/");
        String body = directory ? glob.substring(0, glob.length() - 1) : glob;
        boolean anchored = body.startsWith("/") || body.contains("/");
        if (body.startsWith("/")) {
            body = body.substring(1);
        }
        StringBuilder regex = new StringBuilder(anchored ? "^" : "^(?:.*/)?");
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (body.startsWith("**/", i)) {
                regex.append("(?:.*/)?");
                i += 2;
            } else if (body.startsWith("**", i)) {
                regex.append(".*");
                i++;
            } else if (c == '*') {
                regex.append("[^/]*");
            } else if (c == '?') {
                regex.append("[^/]");
            } else {
                regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        boolean wildcardLast = body.substring(body.lastIndexOf('/') + 1).contains("*");
        regex.append(directory ? "/.*$" : wildcardLast ? "$" : "(?:/.*)?$");
        return Pattern.compile(regex.toString());
    }
}
