package io.testforge.example.gherkinrabbit;

import static io.cucumber.junit.platform.engine.Constants.GLUE_PROPERTY_NAME;
import static io.cucumber.junit.platform.engine.Constants.PLUGIN_PUBLISH_QUIET_PROPERTY_NAME;

import org.junit.platform.suite.api.ConfigurationParameter;
import org.junit.platform.suite.api.IncludeEngines;
import org.junit.platform.suite.api.SelectClasspathResource;
import org.junit.platform.suite.api.Suite;

/**
 * Feature files that need a broker in a container. Their scenarios carry the
 * Gherkin tag {@code @containers}, which the JUnit Platform sees as the tag
 * {@code containers}: the default build skips them,
 * {@code ./gradlew :example-tests:containersTest} runs them.
 */
// a tag filter (containers / not containers) may leave a suite empty in one of the two tasks
@Suite(failIfNoTests = false)
@IncludeEngines("cucumber")
@SelectClasspathResource("features-containers")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME,
        value = "io.testforge.gherkin.glue,io.testforge.gherkin.rabbitmq,io.testforge.example.gherkinrabbit")
@ConfigurationParameter(key = PLUGIN_PUBLISH_QUIET_PROPERTY_NAME, value = "true")
class GherkinRabbitSuite {
}
