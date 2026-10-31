package io.testforge.http;

import io.restassured.response.Response;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Named conditions for {@link ResponsePoller}. The name is what a timeout
 * message prints, so "status 200 and $.status == DONE" reads better than a
 * lambda's class name.
 */
public final class ResponseConditions {

    private ResponseConditions() {
    }

    /** A condition with a readable description. */
    public record Condition(String description, Predicate<Response> test) implements Predicate<Response> {

        public Condition {
            Objects.requireNonNull(description, "description");
            Objects.requireNonNull(test, "test");
        }

        @Override
        public boolean test(Response response) {
            return test.test(response);
        }

        @Override
        public String toString() {
            return description;
        }
    }

    public static Condition status(int expected) {
        return new Condition("status " + expected, response -> response.getStatusCode() == expected);
    }

    /** {@code path} in Groovy GPath, as REST Assured's {@code jsonPath()} reads it, equal to {@code expected}'s text. */
    public static Condition jsonPath(String path, Object expected) {
        String text = String.valueOf(expected);
        return new Condition("$." + path + " == " + text, response -> {
            Object actual = response.jsonPath().get(path);
            return actual != null && text.equals(String.valueOf(actual));
        });
    }

    public static Condition matching(String description, Predicate<Response> test) {
        return new Condition(description, test);
    }

    @SafeVarargs
    public static Condition allOf(Predicate<Response>... conditions) {
        return new Condition(Arrays.stream(conditions).map(String::valueOf).collect(Collectors.joining(" and ")),
                response -> Arrays.stream(conditions).allMatch(condition -> condition.test(response)));
    }
}
