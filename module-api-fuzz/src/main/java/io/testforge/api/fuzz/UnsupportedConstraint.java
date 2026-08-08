package io.testforge.api.fuzz;

/**
 * A promise the document makes that this run decided not to test, and why.
 *
 * <p>The reason is the entire point. "Not exercised" could mean the case budget
 * ran out, or that the constraint sits under a {@code oneOf} where no mutation
 * can be proven invalid, or that the parameter's serialization has no
 * single-valued wire form. Those call for completely different responses from a
 * reader, and a bare count of untested constraints tells them apart from
 * nothing.
 *
 * <p>Making this explicit is what stops the module approximating. Faced with a
 * construct it cannot mutate honestly, it has two options — say so here, or
 * guess — and a guess produces a request the document never described, which
 * makes whatever comes back unattributable.
 */
public record UnsupportedConstraint(String location, String constraint, String reason)
        implements Comparable<UnsupportedConstraint> {

    public DeclaredConstraint declared() {
        return new DeclaredConstraint(location, constraint);
    }

    @Override
    public int compareTo(UnsupportedConstraint other) {
        return declared().compareTo(other.declared());
    }

    @Override
    public String toString() {
        return location + " " + constraint + " — " + reason;
    }
}
