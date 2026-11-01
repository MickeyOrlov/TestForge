package io.testforge.quality;

/** The assessment could not run, or the gate refused the run. */
public class QualityException extends RuntimeException {

    public QualityException(String message) {
        super(message);
    }

    public QualityException(String message, Throwable cause) {
        super(message, cause);
    }
}
