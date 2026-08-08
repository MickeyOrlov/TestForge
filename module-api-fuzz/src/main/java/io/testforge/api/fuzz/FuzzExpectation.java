package io.testforge.api.fuzz;

/**
 * What the document implies should happen to a case's value — and therefore
 * what the run is entitled to conclude from the answer.
 *
 * <p>This is the whole reason schema-aware fuzzing beats random input, and also
 * the thing that is easiest to get wrong. A verdict of {@code OVER_PERMISSIVE}
 * is an accusation: it says the service broke a promise. It may only be made
 * when the document actually contains that promise.
 *
 * <p>The three values are the three categories of case:
 *
 * <ul>
 *   <li>{@link #REJECT} — <b>schema-proven invalid</b>. A declared constraint
 *       says this value is not allowed: past {@code maxLength}, below
 *       {@code minimum}, outside an {@code enum}, wrong type, a required field
 *       removed. Accepting it is a finding.</li>
 *   <li>{@link #ACCEPT} — <b>valid boundary</b>. The value is legal, if
 *       extreme: exactly {@code maxLength}, exactly {@code minimum}. Refusing
 *       it is over-strict validation.</li>
 *   <li>{@link #UNSPECIFIED} — <b>generic robustness probe</b>. The document
 *       says nothing either way: a long string where no length is declared, an
 *       empty string with no {@code minLength}, unusual encodings. Only a crash
 *       or an echo is a finding here; the status code proves nothing.</li>
 * </ul>
 */
public enum FuzzExpectation {

    /** A declared constraint forbids this value. */
    REJECT,

    /** The value is valid; refusing it contradicts the document. */
    ACCEPT,

    /** The document is silent. Nothing about the status code is a finding. */
    UNSPECIFIED
}
