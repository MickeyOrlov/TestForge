package io.testforge.mobile.appium;

import io.appium.java_client.AppiumDriver;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ExtensionContext.Namespace;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.junit.jupiter.api.extension.TestExecutionExceptionHandler;
import org.springframework.test.context.junit.jupiter.SpringExtension;

public class AppiumExtension implements ParameterResolver, TestExecutionExceptionHandler, AfterEachCallback {

    private static final Namespace NAMESPACE = Namespace.create(AppiumExtension.class);
    private static final String SESSIONS_KEY = "sessions";

    @Override
    public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
        Class<?> type = parameterContext.getParameter().getType();
        boolean supported = type == AppiumSession.class || AppiumDriver.class.isAssignableFrom(type);
        if (parameterContext.isAnnotated(MobileDevice.class) && !supported) {
            throw new ParameterResolutionException(
                    "@MobileDevice can only be used on AppiumSession or AppiumDriver parameters, got "
                            + type.getName());
        }
        return supported;
    }

    @Override
    public Object resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
        AppiumDriverFactory factory = SpringExtension.getApplicationContext(extensionContext)
                .getBean(AppiumDriverFactory.class);
        String requestedDevice = requestedDevice(parameterContext, extensionContext);
        Sessions sessions = sessions(extensionContext);
        AppiumSession session = sessions.session(requestedDevice, () -> openSession(factory, requestedDevice));
        Class<?> type = parameterContext.getParameter().getType();
        if (type == AppiumSession.class) {
            return session;
        }
        if (type.isInstance(session.driver())) {
            return session.driver();
        }
        throw new ParameterResolutionException(
                "Requested Appium driver parameter type %s is not assignable from actual driver %s"
                        .formatted(type.getName(), session.driver().getClass().getName()));
    }

    @Override
    public void handleTestExecutionException(ExtensionContext extensionContext, Throwable throwable) throws Throwable {
        Sessions sessions = sessions(extensionContext);
        for (AppiumSession session : sessions.all()) {
            try {
                session.captureFailureArtifacts(testName(extensionContext));
            } catch (RuntimeException e) {
                throwable.addSuppressed(e);
            }
        }
        throw throwable;
    }

    @Override
    public void afterEach(ExtensionContext context) throws Exception {
        sessions(context).close();
    }

    private AppiumSession openSession(AppiumDriverFactory factory, String requestedDevice) {
        if (requestedDevice == null || requestedDevice.isBlank()) {
            return factory.startSession();
        }
        return factory.startSession(requestedDevice);
    }

    private Sessions sessions(ExtensionContext context) {
        ExtensionContext.Store store = context.getStore(NAMESPACE);
        Sessions sessions = store.get(SESSIONS_KEY, Sessions.class);
        if (sessions == null) {
            sessions = new Sessions();
            store.put(SESSIONS_KEY, sessions);
        }
        return sessions;
    }

    private String requestedDevice(ParameterContext parameterContext, ExtensionContext extensionContext) {
        Optional<MobileDevice> parameterDevice = parameterContext.findAnnotation(MobileDevice.class);
        if (parameterDevice.isPresent()) {
            return parameterDevice.get().value();
        }
        Optional<MobileDevice> methodDevice = extensionContext.getTestMethod()
                .flatMap(method -> Optional.ofNullable(method.getAnnotation(MobileDevice.class)));
        if (methodDevice.isPresent()) {
            return methodDevice.get().value();
        }
        return extensionContext.getTestClass()
                .map(type -> type.getAnnotation(MobileDevice.class))
                .map(MobileDevice::value)
                .orElse("");
    }

    private String testName(ExtensionContext context) {
        return context.getTestMethod()
                .map(method -> method.getDeclaringClass().getSimpleName() + "_" + method.getName())
                .orElseGet(context::getDisplayName);
    }

    private static final class Sessions implements AutoCloseable {

        private final Map<String, AppiumSession> sessions = new LinkedHashMap<>();

        AppiumSession session(String requestedDevice, Supplier<AppiumSession> opener) {
            String key = requestedDevice == null || requestedDevice.isBlank() ? "<default>" : requestedDevice;
            return sessions.computeIfAbsent(key, ignored -> opener.get());
        }

        Iterable<AppiumSession> all() {
            return sessions.values();
        }

        @Override
        public void close() throws Exception {
            Exception closeFailure = null;
            for (AppiumSession session : sessions.values()) {
                try {
                    session.close();
                } catch (RuntimeException e) {
                    if (closeFailure == null) {
                        closeFailure = new Exception("Failed to close Appium session", e);
                    } else {
                        closeFailure.addSuppressed(e);
                    }
                }
            }
            sessions.clear();
            if (closeFailure != null) {
                throw closeFailure;
            }
        }
    }
}
