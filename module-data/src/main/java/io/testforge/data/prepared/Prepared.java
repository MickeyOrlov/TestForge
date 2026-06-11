package io.testforge.data.prepared;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a test parameter as "give me a prepared domain object". Resolved by
 * {@link PreparedParameterResolver}: the pool hands out a pre-stocked object
 * when available, otherwise the registered {@link PreparedDataProvider}
 * builds one on the spot.
 *
 * <pre>{@code
 * @Test
 * void earlyRepayment(@Prepared(tags = "active") DemoAgreement agreement) { ... }
 * }</pre>
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface Prepared {

    /** Variant tags the provider understands, e.g. {"active", "pdl"}. */
    String[] tags() default {};
}
