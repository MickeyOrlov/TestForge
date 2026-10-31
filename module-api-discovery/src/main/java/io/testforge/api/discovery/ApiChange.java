package io.testforge.api.discovery;

/**
 * One difference against the baseline and what it means for a client built
 * against the baseline.
 *
 * @param location {@code GET /orders/{id}} for the catalog,
 *                 {@code GET /orders/{id} response 200 $.items[].sku} for a shape
 */
public record ApiChange(String location, Impact impact, String detail) {

    /** Ordered from worst to harmless. */
    public enum Impact {
        /** A client of the baseline can now get or be refused what it cannot handle. */
        BREAKING,
        /** Valid, but likely to surprise a client: a new status, a field it sends no longer described. */
        RISKY,
        /** Not judged: a composed or unresolved schema changed. */
        UNKNOWN,
        /** Nothing a client of the baseline trips over. */
        SAFE
    }

    @Override
    public String toString() {
        return impact + " " + location + ": " + detail;
    }
}
