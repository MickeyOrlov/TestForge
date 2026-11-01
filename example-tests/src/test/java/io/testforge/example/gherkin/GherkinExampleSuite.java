package io.testforge.example.gherkin;

import static io.cucumber.junit.platform.engine.Constants.GLUE_PROPERTY_NAME;
import static io.cucumber.junit.platform.engine.Constants.PLUGIN_PUBLISH_QUIET_PROPERTY_NAME;

import org.junit.platform.suite.api.ConfigurationParameter;
import org.junit.platform.suite.api.IncludeEngines;
import org.junit.platform.suite.api.SelectClasspathResource;
import org.junit.platform.suite.api.Suite;

/**
 * Runs {@code src/test/resources/features} on the JUnit Platform, so Gradle's
 * {@code test} task and the IDE run feature files like any other test.
 *
 * <p>The glue lists TestForge's step packages — lifecycle always, then one
 * per module the project uses — and the project's own steps and Spring
 * context ({@link GherkinExampleContext}).
 */
// a tag filter (containers / not containers) may leave a suite empty in one of the two tasks
@Suite(failIfNoTests = false)
@IncludeEngines("cucumber")
@SelectClasspathResource("features")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME,
        value = "io.testforge.gherkin.glue,io.testforge.gherkin.http,io.testforge.gherkin.kafka,"
                + "io.testforge.gherkin.db,io.testforge.gherkin.mock,io.testforge.example.gherkin")
@ConfigurationParameter(key = PLUGIN_PUBLISH_QUIET_PROPERTY_NAME, value = "true")
// scenarios run in parallel: their own scope keeps stubs, rows and messages apart (parallel.feature)
@ConfigurationParameter(key = "cucumber.execution.parallel.enabled", value = "true")
@ConfigurationParameter(key = "cucumber.execution.parallel.config.strategy", value = "fixed")
@ConfigurationParameter(key = "cucumber.execution.parallel.config.fixed.parallelism", value = "4")
@ConfigurationParameter(key = "cucumber.execution.parallel.config.fixed.max-pool-size", value = "4")
class GherkinExampleSuite {
}
