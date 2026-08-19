package io.testforge.db.contract.model;

/**
 * What the database does to a referencing row when the row it points at is
 * deleted or its key is updated.
 *
 * <p>Modelled because it is behavior consumers rely on, not decoration: turning
 * {@code ON DELETE CASCADE} into {@code RESTRICT} makes deletes that used to
 * succeed start failing, and the reverse makes deletes that used to fail start
 * silently removing rows. Neither is visible in the key's columns or its target.
 */
public enum DbReferentialAction {

    /** The database rejects the parent change while children exist. */
    NO_ACTION,

    /** As {@link #NO_ACTION}, but checked immediately. */
    RESTRICT,

    /** Children are deleted or updated along with the parent. */
    CASCADE,

    /** The referencing columns are set to NULL. */
    SET_NULL,

    /** The referencing columns are set to their default. */
    SET_DEFAULT,

    /** The driver reported an action TestForge does not map. */
    UNKNOWN
}
