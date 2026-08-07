package io.testforge.api.discovery;

public class ApiDiscoveryParseException extends RuntimeException {

    private final ApiSpecSource source;

    public ApiDiscoveryParseException(ApiSpecSource source, String message) {
        super(message);
        this.source = source;
    }

    public ApiSpecSource source() {
        return source;
    }
}
