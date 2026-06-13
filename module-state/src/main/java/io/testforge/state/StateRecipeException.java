package io.testforge.state;

public class StateRecipeException extends RuntimeException {

    public StateRecipeException(String message) {
        super(message);
    }

    public StateRecipeException(String message, Throwable cause) {
        super(message, cause);
    }
}
