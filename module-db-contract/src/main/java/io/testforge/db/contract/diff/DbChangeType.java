package io.testforge.db.contract.diff;

/**
 * The bounded set of structural changes TestForge detects between two
 * {@link io.testforge.db.contract.model.DbSchemaSnapshot}s.
 *
 * <p>This is a closed vocabulary, not an attempt to describe every possible DDL
 * operation. A change TestForge cannot express as one of these types is not
 * reported — the module compares two already-normalized structures and never
 * parses SQL or migration scripts.
 */
public enum DbChangeType {

    /** A table present in the current schema is absent from the baseline. */
    TABLE_ADDED,
    /** A table present in the baseline is absent from the current schema. */
    TABLE_REMOVED,

    /** A column present in the current table is absent from the baseline. */
    COLUMN_ADDED,
    /** A column present in the baseline table is absent from the current schema. */
    COLUMN_REMOVED,
    /** The column's logical type family changed (for example INTEGER to CHARACTER). */
    COLUMN_TYPE_FAMILY_CHANGED,
    /** The physical (vendor) type changed while the logical type family stayed the same. */
    COLUMN_PHYSICAL_TYPE_CHANGED,
    /** The column went from nullable to NOT NULL. */
    COLUMN_NULLABILITY_TIGHTENED,
    /** The column went from NOT NULL to nullable. */
    COLUMN_NULLABILITY_RELAXED,
    /** The column gained a DEFAULT clause. */
    COLUMN_DEFAULT_ADDED,
    /** The column lost its DEFAULT clause. */
    COLUMN_DEFAULT_REMOVED,

    /** A table that had no primary key now has one. */
    PRIMARY_KEY_ADDED,
    /** A table that had a primary key no longer has one. */
    PRIMARY_KEY_REMOVED,
    /** The primary key's column list changed. */
    PRIMARY_KEY_COLUMNS_CHANGED,

    /** A foreign key present in the current table is absent from the baseline. */
    FOREIGN_KEY_ADDED,
    /** A foreign key present in the baseline table is absent from the current schema. */
    FOREIGN_KEY_REMOVED,
    /** A foreign key of the same name now references different columns or a different table. */
    FOREIGN_KEY_CHANGED,

    /** An index present in the current table is absent from the baseline. */
    INDEX_ADDED,
    /** An index present in the baseline table is absent from the current schema. */
    INDEX_REMOVED,
    /** An index of the same name now covers different columns. */
    INDEX_COLUMNS_CHANGED,
    /** An existing index became unique. */
    INDEX_UNIQUENESS_TIGHTENED,
    /** An existing index stopped being unique. */
    INDEX_UNIQUENESS_RELAXED
}
