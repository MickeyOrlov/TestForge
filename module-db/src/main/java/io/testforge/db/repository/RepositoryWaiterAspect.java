package io.testforge.db.repository;

import io.testforge.db.DbWaiter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;

@Aspect
public class RepositoryWaiterAspect {

    private final DbWaiter dbWaiter;

    public RepositoryWaiterAspect(DbWaiter dbWaiter) {
        this.dbWaiter = dbWaiter;
    }

    @Around("this(org.springframework.data.repository.Repository) && execution(* wait*(..))")
    public Object awaitRepositoryMethod(ProceedingJoinPoint joinPoint) throws Throwable {
        Method waitMethod = ((MethodSignature) joinPoint.getSignature()).getMethod();
        Method queryMethod = queryMethod(waitMethod);
        String description = waitMethod.getDeclaringClass().getSimpleName() + "." + queryMethod.getName();

        if (Optional.class.isAssignableFrom(queryMethod.getReturnType())) {
            Object row = dbWaiter.awaitRow(description, () -> optionalResult(joinPoint, queryMethod));
            return Optional.class.isAssignableFrom(waitMethod.getReturnType()) ? Optional.ofNullable(row) : row;
        }

        if (List.class.isAssignableFrom(queryMethod.getReturnType())) {
            return dbWaiter.awaitRows(description, () -> listResult(joinPoint, queryMethod), 1);
        }

        return dbWaiter.awaitRow(description, () -> Optional.ofNullable(invoke(joinPoint, queryMethod)));
    }

    private Method queryMethod(Method waitMethod) {
        String waitName = waitMethod.getName();
        if (!waitName.startsWith("wait") || waitName.length() == "wait".length()) {
            throw new IllegalStateException("Repository wait method must be named waitBy... or waitSomething...");
        }

        String queryName = "find" + waitName.substring("wait".length());
        try {
            return waitMethod.getDeclaringClass().getMethod(queryName, waitMethod.getParameterTypes());
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(
                    "Repository wait method '%s' expects query method '%s' with the same parameters"
                            .formatted(waitName, queryName),
                    e);
        }
    }

    @SuppressWarnings("unchecked")
    private Optional<Object> optionalResult(ProceedingJoinPoint joinPoint, Method queryMethod) {
        return (Optional<Object>) invoke(joinPoint, queryMethod);
    }

    @SuppressWarnings("unchecked")
    private List<Object> listResult(ProceedingJoinPoint joinPoint, Method queryMethod) {
        return (List<Object>) invoke(joinPoint, queryMethod);
    }

    private Object invoke(ProceedingJoinPoint joinPoint, Method queryMethod) {
        try {
            return queryMethod.invoke(joinPoint.getThis(), joinPoint.getArgs());
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot invoke repository query method: " + queryMethod, e);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("Repository query method failed: " + queryMethod, cause);
        }
    }
}
