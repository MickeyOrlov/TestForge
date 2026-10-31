package io.testforge.auth;

import java.util.Optional;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ExtensionContext.Namespace;
import org.junit.platform.commons.support.AnnotationSupport;

/** Applies {@link AsUser} around each test and restores the previous role afterwards. */
public class AsUserExtension implements BeforeEachCallback, AfterEachCallback {

    private static final Namespace NAMESPACE = Namespace.create(AsUserExtension.class);
    private static final String PREVIOUS = "previous";
    private static final String APPLIED = "applied";

    @Override
    public void beforeEach(ExtensionContext context) {
        Optional<AsUser> annotation = AnnotationSupport.findAnnotation(context.getTestMethod(), AsUser.class)
                .or(() -> context.getTestClass().flatMap(AsUserExtension::findOnClassOrEnclosing));
        annotation.ifPresent(asUser -> {
            context.getStore(NAMESPACE).put(PREVIOUS, AuthContext.current().orElse(null));
            context.getStore(NAMESPACE).put(APPLIED, true);
            AuthContext.set(asUser.value());
        });
    }

    /** {@code @Inherited} does not reach the outer class of a {@code @Nested} test; walk it explicitly. */
    private static Optional<AsUser> findOnClassOrEnclosing(Class<?> testClass) {
        for (Class<?> current = testClass; current != null; current = current.getEnclosingClass()) {
            Optional<AsUser> found = AnnotationSupport.findAnnotation(current, AsUser.class);
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    @Override
    public void afterEach(ExtensionContext context) {
        if (Boolean.TRUE.equals(context.getStore(NAMESPACE).remove(APPLIED, Boolean.class))) {
            AuthContext.restore(context.getStore(NAMESPACE).remove(PREVIOUS, String.class));
        }
    }
}
