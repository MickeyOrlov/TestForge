package io.testforge.quality;

/** A dimension's verdict, worst last. */
public enum QualityStatus {

    /** The run held nothing this dimension can be judged on — never read as a pass. */
    NOT_MEASURED,
    PASS,
    WARN,
    FAIL;

    boolean worseThan(QualityStatus other) {
        return ordinal() > other.ordinal();
    }
}
