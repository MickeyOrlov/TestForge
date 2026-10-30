package io.testforge.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** "Every new module ships: auto-configuration + *Properties record + README + example test". */
class ModuleConventionTest {

    private static final String IMPORTS_FILE =
            "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports";

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("io.testforge");

    private static final Pattern REFERENCE =
            Pattern.compile("^(?:import(?: static)?|package)\\s+(io\\.testforge\\.[\\w.]+)", Pattern.MULTILINE);

    private static final Path ROOT = Path.of(System.getProperty("testforge.root"));

    @Test
    void gradleAndModuleMapAgree() {
        Set<String> gradle = new TreeSet<>(Arrays.asList(System.getProperty("testforge.modules").split(",")));

        assertThat(Modules.ROOT_PACKAGES.keySet())
                .as("Modules.ROOT_PACKAGES vs production modules in settings.gradle")
                .containsExactlyInAnyOrderElementsOf(gradle);
    }

    @Test
    void everyModuleHasOneAutoConfigurationListedInItsImportsFile() throws IOException {
        Set<String> listed = listedAutoConfigurations();
        List<String> problems = new ArrayList<>();

        for (String module : Modules.ROOT_PACKAGES.keySet()) {
            List<String> configs = CLASSES.stream()
                    .filter(c -> module.equals(Modules.moduleOf(c.getName())))
                    .filter(c -> c.isAnnotatedWith(AutoConfiguration.class))
                    .map(JavaClass::getName)
                    .toList();
            if (configs.isEmpty()) {
                problems.add(module + ": no @AutoConfiguration class");
            }
            for (String config : configs) {
                if (!config.endsWith("AutoConfiguration") || !simpleName(config).startsWith("TestForge")) {
                    problems.add(module + ": " + config + " should be named TestForge*AutoConfiguration");
                }
                if (!listed.contains(config)) {
                    problems.add(module + ": " + config + " is missing from " + IMPORTS_FILE);
                }
            }
        }
        for (String name : listed) {
            if (name.startsWith("io.testforge.") && !CLASSES.contain(name)) {
                problems.add(IMPORTS_FILE + " lists a class that does not exist: " + name);
            }
        }

        assertThat(problems).isEmpty();
    }

    @Test
    void propertiesAreRecordsUnderTheForgePrefix() {
        List<String> problems = new ArrayList<>();
        for (JavaClass clazz : CLASSES) {
            if (clazz.getSimpleName().endsWith("Properties") && clazz.isAnnotatedWith(ConfigurationProperties.class)) {
                if (!clazz.isRecord()) {
                    problems.add(clazz.getName() + " is not a record");
                }
                ConfigurationProperties annotation = clazz.getAnnotationOfType(ConfigurationProperties.class);
                String prefix = annotation.prefix().isEmpty() ? annotation.value() : annotation.prefix();
                if (!prefix.startsWith("forge.")) {
                    problems.add(clazz.getName() + " binds to '" + prefix + "', expected forge.*");
                }
            }
        }

        assertThat(problems).isEmpty();
    }

    @Test
    void everyModuleHasReadmeBuildFileAndAgentGuide() {
        List<String> missing = new ArrayList<>();
        for (String module : Modules.ROOT_PACKAGES.keySet()) {
            for (String file : List.of("README.md", "build.gradle", "AGENTS.md")) {
                if (!Files.isRegularFile(ROOT.resolve(module).resolve(file))) {
                    missing.add(module + "/" + file);
                }
            }
        }

        assertThat(missing).isEmpty();
    }

    @Test
    void everyModuleIsExercisedByAnExampleOrOwnTest() throws IOException {
        Set<String> exercised = new TreeSet<>(modulesReferencedBy(ROOT.resolve("example-tests/src/test")));
        for (String module : Modules.ROOT_PACKAGES.keySet()) {
            if (modulesReferencedBy(ROOT.resolve(module).resolve("src/test")).contains(module)) {
                exercised.add(module);
            }
        }

        assertThat(Modules.ROOT_PACKAGES.keySet())
                .as("modules with neither their own tests nor an example test importing them")
                .allMatch(exercised::contains);
    }

    @Test
    void dockerfileCopiesEveryModuleBuildFile() throws IOException {
        String settings = Files.readString(ROOT.resolve("settings.gradle"));
        String dockerfile = Files.readString(ROOT.resolve("Dockerfile"));
        Matcher include = Pattern.compile("\"([a-z0-9-]+)\"").matcher(
                settings.substring(settings.indexOf("include")));
        List<String> missing = new ArrayList<>();
        while (include.find()) {
            String module = include.group(1);
            if (!dockerfile.contains("COPY %s/build.gradle %s/build.gradle".formatted(module, module))) {
                missing.add(module);
            }
        }

        assertThat(missing)
                .as("the runner image's warmup `gradlew help` fails without these build files")
                .isEmpty();
    }

    private static Set<String> listedAutoConfigurations() throws IOException {
        Set<String> names = new TreeSet<>();
        var resources = ModuleConventionTest.class.getClassLoader().getResources(IMPORTS_FILE);
        while (resources.hasMoreElements()) {
            try (var in = resources.nextElement().openStream()) {
                new String(in.readAllBytes()).lines()
                        .map(String::trim)
                        .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                        .forEach(names::add);
            }
        }
        return names;
    }

    /** Modules whose classes are imported, or whose package is declared, by test sources under {@code dir}. */
    private static Set<String> modulesReferencedBy(Path dir) throws IOException {
        Set<String> modules = new TreeSet<>();
        if (!Files.isDirectory(dir)) {
            return modules;
        }
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                Matcher matcher = REFERENCE.matcher(Files.readString(file));
                while (matcher.find()) {
                    String module = Modules.moduleOf(matcher.group(1));
                    if (module != null) {
                        modules.add(module);
                    }
                }
            }
        }
        return modules;
    }

    private static String simpleName(String className) {
        return className.substring(className.lastIndexOf('.') + 1);
    }
}
