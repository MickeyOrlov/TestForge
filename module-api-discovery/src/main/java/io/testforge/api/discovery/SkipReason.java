package io.testforge.api.discovery;

/**
 * Why an endpoint was not called. Every entry in the catalog carries one of
 * these or nothing at all, so a reader can tell at a glance whether an endpoint
 * was left alone on purpose or because something is missing.
 */
public enum SkipReason {

    /** Probing is off — the run is a plan, not an action. */
    DISABLED_PROBE("probing is disabled (forge.api-discovery.probe.enabled)"),

    PATH_DENIED("path matches probe.deny-paths"),

    PATH_NOT_INCLUDED("path does not match probe.include-paths"),

    /** The operation opted out through the vendor extension. */
    VENDOR_OPT_OUT("the operation opts out through the vendor extension"),

    DEPRECATED("the operation is deprecated"),

    /** The method is outside probe.methods and the unsafe gates are not all open. */
    UNSAFE_METHOD("method is not in probe.methods"),

    DELETE_NOT_ALLOWED("DELETE requires probe.unsafe.allow-delete"),

    /** An unsafe method may only use values a human configured. */
    UNSAFE_PARAMETER_SOURCE("unsafe methods accept configured parameter values only"),

    MISSING_PATH_PARAMETER("no value for a path parameter"),

    MISSING_REQUIRED_PARAM("no value for a required query parameter"),

    MAX_ENDPOINTS_REACHED("probe.max-endpoints reached");

    private final String description;

    SkipReason(String description) {
        this.description = description;
    }

    public String description() {
        return description;
    }
}
