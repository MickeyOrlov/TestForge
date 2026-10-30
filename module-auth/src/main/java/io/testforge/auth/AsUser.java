package io.testforge.auth;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Runs a test (or every test of a class) as a configured role: requests sent
 * through the API client carry that role's token.
 *
 * <pre>{@code
 * @Test
 * @AsUser("admin")
 * void adminCanCloseAnAccount() { ... }
 * }</pre>
 *
 * <p>A method annotation wins over a class annotation.
 */
@Documented
@Inherited
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@ExtendWith(AsUserExtension.class)
public @interface AsUser {

    /** A role under {@code forge.auth.roles}. */
    String value();
}
