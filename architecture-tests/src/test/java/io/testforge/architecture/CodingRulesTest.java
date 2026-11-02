package io.testforge.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.GeneralCodingRules;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Conventions from AGENTS.md that are cheap to state and easy to break by accident. */
class CodingRulesTest {

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("io.testforge");

    @Test
    void noThreadSleepAnywhere() {
        ArchRule rule = noClasses()
                .should().callMethod(Thread.class, "sleep", long.class)
                .orShould().callMethod(Thread.class, "sleep", long.class, int.class)
                .orShould().callMethod(Thread.class, "sleep", java.time.Duration.class)
                .orShould().callMethod(TimeUnit.class, "sleep", long.class)
                .because("use Waiter/DbWaiter; a sleep means a polling condition is missing");
        rule.check(CLASSES);
    }

    @Test
    void noLombokInTemplateCode() {
        noClasses().should().dependOnClassesThat().resideInAPackage("lombok..")
                .because("adapters may add Lombok, the template does not")
                .check(CLASSES);
    }

    @Test
    void runFilesComeFromNewFileNotAFixedNameInTheSharedDirectory() {
        noClasses().that().resideOutsideOfPackages("io.testforge.artifact..", "io.testforge.reporting..")
                .should().callMethod("io.testforge.artifact.ArtifactSink", "directoryFor", "java.lang.String")
                .because("every Spring context of a test JVM writes into the same run directory: a file under a "
                        + "fixed name keeps only the context that closed last — take it from "
                        + "ArtifactSink.newFile(source, name)")
                .check(CLASSES);
    }

    @Test
    void noStandardStreams() {
        GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS.check(CLASSES);
    }

    @Test
    void noJavaUtilLogging() {
        GeneralCodingRules.NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING.check(CLASSES);
    }

    @Test
    void noFieldInjection() {
        GeneralCodingRules.NO_CLASSES_SHOULD_USE_FIELD_INJECTION.check(CLASSES);
    }
}
