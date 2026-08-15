package io.testforge.api.fuzz;

/**
 * Exception thrown when API fuzzing operations encounter errors or failure conditions.
 */
public class ApiFuzzException extends RuntimeException {

    public ApiFuzzException(String message) {
        super(message);
    }

    public ApiFuzzException(String message, Throwable cause) {
        super(message, cause);
    }
}
