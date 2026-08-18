package io.testforge.db.contract.model;

import io.testforge.db.schema.ColumnTypeFamily;

/**
 * One column of the normalized TestForge database contract model.
 *
 * <p>Deliberately bounded: v1 records the logical type family, the physical
 * (vendor) type, nullability and whether a default exists. Collation, identity
 * generation, comments, privileges and generated-column expressions are not
 * modelled — see the module README for the full v1 boundary.
 *
 * @param name        column name exactly as the database reports it
 * @param typeFamily  logical type family, {@link ColumnTypeFamily#UNKNOWN} when
 *                    the JDBC type is one TestForge does not map
 * @param type        physical type as the vendor names it, including width or
 *                    precision when the driver reports one (e.g. {@code varchar(255)})
 * @param nullable    whether the database allows NULL in this column
 * @param hasDefault  whether the database supplies a value when a writer omits
 *                    this column — a DEFAULT clause, an identity definition or a
 *                    generated expression all count, because the policy asks this
 *                    field one question: does an existing INSERT that never names
 *                    the column still succeed? The default <em>expression</em> is
 *                    not recorded; it is vendor-specific noise the policy never reads
 */
public record DbColumn(
        String name,
        ColumnTypeFamily typeFamily,
        String type,
        boolean nullable,
        boolean hasDefault) {

    public DbColumn {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Column name must not be null or blank");
        }
        if (typeFamily == null) {
            typeFamily = ColumnTypeFamily.UNKNOWN;
        }
        if (type == null) {
            type = "";
        }
    }

    /**
     * Human-readable one-line description used in diffs and reports.
     *
     * @return the description, e.g. {@code varchar(255) [CHARACTER] NULL DEFAULT}
     */
    public String describe() {
        StringBuilder out = new StringBuilder();
        out.append(type.isBlank() ? typeFamily.name() : type);
        out.append(" [").append(typeFamily.name()).append(']');
        out.append(nullable ? " NULL" : " NOT NULL");
        if (hasDefault) {
            out.append(" DEFAULT");
        }
        return out.toString();
    }
}
