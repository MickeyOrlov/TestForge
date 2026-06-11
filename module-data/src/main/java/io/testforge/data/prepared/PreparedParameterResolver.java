package io.testforge.data.prepared;

import java.util.List;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.springframework.test.context.junit.jupiter.SpringExtension;

/**
 * JUnit 5 fixture-style injection: a test declares what it needs in its
 * signature, the pool decides how to get it.
 *
 * <pre>{@code
 * @SpringBootTest
 * @ExtendWith(PreparedParameterResolver.class)
 * class RepaymentTest {
 *
 *     @Test
 *     void earlyRepayment(@Prepared(tags = "active") DemoAgreement agreement) { ... }
 * }
 * }</pre>
 */
public class PreparedParameterResolver implements ParameterResolver {

    @Override
    public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
        return parameterContext.isAnnotated(Prepared.class);
    }

    @Override
    public Object resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
        Prepared prepared = parameterContext.findAnnotation(Prepared.class).orElseThrow();
        PreparedDataPool pool = SpringExtension.getApplicationContext(extensionContext)
                .getBean(PreparedDataPool.class);
        return pool.acquire(parameterContext.getParameter().getType(), List.of(prepared.tags()));
    }
}
