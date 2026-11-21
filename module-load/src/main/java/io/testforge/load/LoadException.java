package io.testforge.load;

/** A configuration error, or a load run that did not pass; raw process output is never included. */
public class LoadException extends RuntimeException {
    public LoadException(String message) {
        super(message);
    }
}
