package io.testforge.example;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.testforge.mobile.appium.AppiumDriverFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Wiring check without a device farm: enabling the module yields a configured
 * factory; actually starting a session requires an Appium server, which a
 * template cannot assume. Real mobile tests belong to the adapted project.
 */
@SpringBootTest(properties = {
        "forge.mobile.appium.enabled=true",
        "forge.mobile.appium.hub-url=not a url",
        "forge.mobile.appium.device-name=emulator-5554"
})
class AppiumWiringTest {

    @Autowired
    AppiumDriverFactory factory;

    @Test
    void factoryIsConfiguredFromProperties() {
        assertThat(factory).isNotNull();
    }

    @Test
    void brokenHubUrlFailsFastWithReadableMessage() {
        assertThatThrownBy(factory::startSession)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid Appium hub URL");
    }
}
