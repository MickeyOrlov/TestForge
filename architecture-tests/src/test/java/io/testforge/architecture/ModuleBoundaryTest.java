package io.testforge.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** "Modules must remain deletable" and "core stays thin", as executable rules. */
class ModuleBoundaryTest {

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("io.testforge");

    @Test
    void everyProductionClassBelongsToAKnownModule() {
        List<String> orphans = CLASSES.stream()
                .map(JavaClass::getName)
                .filter(name -> Modules.moduleOf(name) == null)
                .sorted()
                .toList();

        assertThat(orphans)
                .as("classes outside every package registered in Modules.ROOT_PACKAGES")
                .isEmpty();
    }

    @Test
    void modulesOnlyDependOnDeclaredNeighbours() {
        List<String> violations = new ArrayList<>();
        for (JavaClass clazz : CLASSES) {
            String from = Modules.moduleOf(clazz.getName());
            if (from == null) {
                continue;
            }
            for (var dependency : clazz.getDirectDependenciesFromSelf()) {
                String to = Modules.moduleOf(dependency.getTargetClass().getName());
                if (to == null || to.equals(from) || to.equals("core")) {
                    continue;
                }
                if (!Modules.allowedFor(from).contains(to)) {
                    violations.add(from + " -> " + to + " via " + dependency.getDescription());
                }
            }
        }

        assertThat(violations)
                .as("add the edge to Modules.ALLOWED_DEPENDENCIES only if it is intended")
                .isEmpty();
    }

    @Test
    void coreDependsOnNoOtherModule() {
        List<String> violations = new ArrayList<>();
        for (JavaClass clazz : CLASSES) {
            if (!"core".equals(Modules.moduleOf(clazz.getName()))) {
                continue;
            }
            for (var dependency : clazz.getDirectDependenciesFromSelf()) {
                String to = Modules.moduleOf(dependency.getTargetClass().getName());
                if (to != null && !to.equals("core")) {
                    violations.add(dependency.getDescription());
                }
            }
        }

        assertThat(violations).isEmpty();
    }

    @Test
    void moduleDependencyGraphHasNoCycles() {
        for (var entry : Modules.ALLOWED_DEPENDENCIES.entrySet()) {
            for (String target : entry.getValue()) {
                assertThat(Modules.allowedFor(target))
                        .as("%s <-> %s", entry.getKey(), target)
                        .doesNotContain(entry.getKey());
            }
        }
    }
}
