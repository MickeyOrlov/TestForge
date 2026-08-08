package io.testforge.api.fuzz;

/**
 * One promise the document makes about one input: {@code $.name minLength},
 * {@code query:limit maximum}.
 */
public record DeclaredConstraint(String location, String constraint) implements Comparable<DeclaredConstraint> {

    @Override
    public int compareTo(DeclaredConstraint other) {
        int byLocation = location.compareTo(other.location);
        return byLocation != 0 ? byLocation : constraint.compareTo(other.constraint);
    }

    @Override
    public String toString() {
        return location + " " + constraint;
    }
}
