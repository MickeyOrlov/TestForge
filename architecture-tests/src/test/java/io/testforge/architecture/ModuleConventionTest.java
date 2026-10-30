package io.testforge.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
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
    void everyModuleHasReadmeAndBuildFile() {
        List<String> missing = new ArrayList<>();
        for (String module : Modules.ROOT_PACKAGES.keySet()) {
            for (String file : List.of("README.md", "build.gradle")) {
                if (!Files.isRegularFile(ROOT.resolve(module).resolve(file))) {
                    missing.add(module + "/" + file);
                }
            }
        }

        assertThat(missing).isEmpty();
    }

    @Test
    void everyModuleIsExercisedByAnExampleOrOwnTest() throws IOException {
        List<String> untested = new ArrayList<>();
        for (var entry : Modules.ROOT_PACKAGES.entrySet()) {
            if (entry.getKey().equals("core")) {
                continue;
            }
            String root = entry.getValue();
            boolean ownTest = hasJavaSource(ROOT.resolve(entry.getKey()).resolve("src/test"), root);
            boolean example = hasJavaSource(ROOT.resolve("example-tests/src/test"), root);
            if (!ownTest && !example) {
                untested.add(entry.getKey());
            }
        }

        assertThat(untested)
                .as("modules with neither their own tests nor an example test importing their package")
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

    private static boolean hasJavaSource(Path dir, String packageRoot) throws IOException {
        if (!Files.isDirectory(dir)) {
            return false;
        }
        try (Stream<Path> files = Files.walk(dir)) {
            return files.filter(p -> p.toString().endsWith(".java")).anyMatch(p -> {
                try {
                    String text = Files.readString(p);
                    return text.contains("import " + packageRoot + ".") || text.contains("package " + packageRoot);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
    }

    private static String simpleName(String className) {
        return className.substring(className.lastIndexOf('.') + 1);
    }
}
