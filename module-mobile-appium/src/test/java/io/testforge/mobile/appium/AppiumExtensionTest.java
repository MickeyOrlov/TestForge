package io.testforge.mobile.appium;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.appium.java_client.AppiumDriver;
import java.lang.reflect.Parameter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;

class AppiumExtensionTest {

    @SuppressWarnings("unused")
    private void sample(@MobileDevice("x") String wrongType, AppiumSession session, AppiumDriver driver) {
    }

    @Test
    void mobileDeviceOnUnsupportedParameterTypeFailsClearly() throws Exception {
        ParameterContext context = mock(ParameterContext.class);
        when(context.getParameter()).thenReturn(parameter(0));
        when(context.isAnnotated(MobileDevice.class)).thenReturn(true);

        assertThatThrownBy(() -> new AppiumExtension().supportsParameter(context, null))
                .isInstanceOf(ParameterResolutionException.class)
                .hasMessageContaining("@MobileDevice can only be used on AppiumSession or AppiumDriver");
    }

    @Test
    void supportsSessionAndDriverParameters() throws Exception {
        ParameterContext sessionContext = mock(ParameterContext.class);
        when(sessionContext.getParameter()).thenReturn(parameter(1));
        ParameterContext driverContext = mock(ParameterContext.class);
        when(driverContext.getParameter()).thenReturn(parameter(2));

        AppiumExtension extension = new AppiumExtension();
        assertThat(extension.supportsParameter(sessionContext, null)).isTrue();
        assertThat(extension.supportsParameter(driverContext, null)).isTrue();
    }

    private Parameter parameter(int index) throws NoSuchMethodException {
        return getClass()
                .getDeclaredMethod("sample", String.class, AppiumSession.class, AppiumDriver.class)
                .getParameters()[index];
    }
}
