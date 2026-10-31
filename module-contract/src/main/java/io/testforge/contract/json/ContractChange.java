package io.testforge.contract.json;

/**
 * One difference between two versions of a schema and what it means for a
 * consumer built against the older one.
 *
 * @param path {@code $.payload.status}, {@code $.items[].sku}
 */
public record ContractChange(String path, Impact impact, String detail) {

    /** Ordered from worst to harmless. */
    public enum Impact {
        /** A consumer of the old contract can now receive what it cannot handle. */
        BREAKING,
        /** Valid for the schema, likely to surprise someone: a new enum value, an extra field for a strict reader. */
        RISKY,
        /** Not judged: composition or a {@code $ref} changed. */
        UNKNOWN,
        /** Nothing a consumer of the old contract can trip over. */
        SAFE
    }

    @Override
    public String toString() {
        return impact + " " + path + ": " + detail;
    }
}
